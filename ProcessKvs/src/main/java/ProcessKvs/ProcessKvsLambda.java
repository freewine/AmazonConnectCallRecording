package ProcessKvs;

import ProcessKvs.audio.AudioStreamService;
import ProcessKvs.audio.AudioUtils;
import ProcessKvs.model.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import org.joda.time.DateTime;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.connect.ConnectClient;
import software.amazon.awssdk.services.connect.model.UpdateContactAttributesRequest;
import software.amazon.awssdk.services.connect.model.UpdateContactAttributesResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for requests to Lambda function.
 */
public class ProcessKvsLambda implements RequestHandler<KinesisEvent, String> {
    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    //private static final String DDB_TABLE = System.getenv("DDB_TABLE");
    private static final Logger logger = LoggerFactory.getLogger(ProcessKvsLambda.class);

    @Override
    public String handleRequest(KinesisEvent kinesisEvent, Context context) {
        System.out.println("Processing CTR Event");

        for (KinesisEvent.KinesisEventRecord record : kinesisEvent.getRecords()) {
            try {
                String recordData = new String(record.getKinesis().getData().array());
                System.out.println("Record Data: " + recordData);
                processRecord(recordData);
            } catch (Exception e) {
                // if json does not contain required data, will exit early
                System.out.println(e.toString());
            }
        }

        return "{ \"result\": \"Success\" }";
    }

    private void processRecord(String data) {
        JSONObject json = new JSONObject(data);
        ContactTraceRecord traceRecord = new ContactTraceRecord(json);
        List<KVStreamRecordingData> recordings = traceRecord.getRecordings();

        if (recordings.isEmpty()) {
            logger.info("No Voice recording, skipped");
            return;
        }

        int recordingAuth = traceRecord.getAttributes().getRecordingAuth();

        if ((recordingAuth <= AudioUtils.AUTH_AUDIO_NONE || recordingAuth > AudioUtils.AUTH_AUDIO_MIXED)) {
            logger.info("Recording is not authorized, skipped. recordingAuth:" + recordingAuth);
            return;
        }

        ConnectAttributesData connectAttributes = new ConnectAttributesData();

        //A CTR may include multi recordings, event multi types of recordings, we only process the type of KINESIS_VIDEO_STREAM
        /*
         "Recordings": [
            {
                "DeletionReason": null,
                "FragmentStartNumber": null,
                "FragmentStopNumber": null,
                "Location": "amazon-connect-06b674617501/connect/freewine-oregon/CallRecordings/2024/01/26/e81b7b4d-20ac-472d-8aad-0d848ce7c9b4_20240126T03:07_UTC.wav",
                "MediaStreamType": "AUDIO",
                "ParticipantType": null,
                "StartTimestamp": null,
                "Status": "AVAILABLE",
                "StopTimestamp": null,
                "StorageType": "S3"
            },
            {
                "DeletionReason": null,
                "FragmentStartNumber": "91343852333181432392682062659924109276236173057",
                "FragmentStopNumber": "91343852333181432546186627531311271605344142199",
                "Location": "arn:aws:kinesisvideo:us-west-2:032998046382:stream/recording-connect-freewine-oregon-contact-3148efdd-9507-4148-8fab-9e336b74a038/1706169572895",
                "MediaStreamType": "VIDEO",
                "ParticipantType": "CUSTOMER",
                "StartTimestamp": "2024-01-26T03:07:41Z",
                "Status": null,
                "StopTimestamp": "2024-01-26T03:08:12Z",
                "StorageType": "KINESIS_VIDEO_STREAM"
            }
          ],
         */
        for(KVStreamRecordingData recording: recordings) {
            if(!recording.getStorageType().equals("KINESIS_VIDEO_STREAM")) {
                logger.info("Recording StorageType is not KINESIS_VIDEO_STREAM, skipped. StorageType:" + recording.getStorageType());
                continue;
            }

            //System.out.println(event);
            RecordingData recordingData = parseEvent(traceRecord, recording);

            // Begin processing audio stream
            AudioStreamService streamingService = new AudioStreamService();
            try {
                streamingService.processAudioStream(recordingData);
                //saveToDdb(recordingData);

                logger.info(String.format("fromCustomer: %s, toCustomer: %s, mixed: %s", recordingData.getAudioFromCustomer(), recordingData.getAudioToCustomer(), recordingData.getAudioMixed()));
                //append audio file path to connect attributes
                if(recordingData.getAudioFromCustomer() != null && (!recordingData.getAudioFromCustomer().isEmpty())) {
                    if(connectAttributes.getAudioFromCustomer() == null || connectAttributes.getAudioFromCustomer().isEmpty()) {
                        connectAttributes.setAudioFromCustomer(recordingData.getAudioFromCustomer());
                    } else {
                        connectAttributes.setAudioFromCustomer(connectAttributes.getAudioFromCustomer()  + ", " +recordingData.getAudioFromCustomer());
                    }
                }

                if(recordingData.getAudioToCustomer() != null && (!recordingData.getAudioToCustomer().isEmpty())) {
                    if(connectAttributes.getAudioToCustomer() == null || connectAttributes.getAudioToCustomer().isEmpty()) {
                        connectAttributes.setAudioToCustomer(recordingData.getAudioToCustomer());
                    } else {
                        connectAttributes.setAudioToCustomer(connectAttributes.getAudioToCustomer()  + ", " +recordingData.getAudioToCustomer());
                    }
                }

                if(recordingData.getAudioMixed() != null && (!recordingData.getAudioMixed().isEmpty())) {
                    if(connectAttributes.getAudioMixed() == null || connectAttributes.getAudioMixed().isEmpty()) {
                        connectAttributes.setAudioMixed(recordingData.getAudioMixed());
                    } else {
                        connectAttributes.setAudioMixed(connectAttributes.getAudioMixed()  + ", " +recordingData.getAudioMixed());
                    }
                }
            } catch (Exception e) {
                logger.error("KVS processing failed with: ", e);
            }
        }
        //updateConnectContactAttributes(traceRecord, connectAttributes);
    }

    private RecordingData parseEvent(ContactTraceRecord traceRecord, KVStreamRecordingData recording) {

        return RecordingData.builder()
                .withAwsRegion(REGION.getName())
                .withRecordingAuth(traceRecord.getAttributes().getRecordingAuth())
                .withContactId(traceRecord.getContactId())
                .withInitialContactId(traceRecord.getInitialContactId())
                .withInstanceARN(traceRecord.getInstanceARN())
                .withCustomerNumber(traceRecord.getCustomerEndpoint().getAddress())
                .withLanguageCode(traceRecord.getAttributes().getLanguageCode())
                //.withAgentName(traceRecord.getAttributes().getAgentName())
                .withStreamARN(recording.getLocation())
                .withStartFragmentNum(recording.getFragmentStartNumber())
                .withStartTimestamp(recording.getStartTimestamp())
                .withStopFragmentNumber(recording.getFragmentStopNumber())
                .withStopTimestamp(recording.getStopTimestamp())
                .withDateTime(new DateTime())
                .build();
    }

    private void saveToDdb(RecordingData data) {
        // Create a DynamoDB client
        DynamoDbClient ddb = DynamoDbClient.builder()
                .region(Region.of(REGION.getName()))
                .build();

        // Create the item
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("contactId", AttributeValue.builder().s(data.getContactId()).build());
        item.put("dateTime", AttributeValue.builder().s(data.getDateTime().toString()).build());
        item.put("awsRegion", AttributeValue.builder().s(data.getAwsRegion() == null ? REGION.getName() : data.getAwsRegion()).build());
        item.put("recordingAuth", AttributeValue.builder().s(String.valueOf(data.getRecordingAuth())).build());
        item.put("languageCode", AttributeValue.builder().s(data.getLanguageCode() == null ? "" : data.getLanguageCode()).build());
        //item.put("agentName", AttributeValue.builder().s(data.getAgentName() == null ? "" : data.getAgentName()).build());
        item.put("audioFromCustomer", AttributeValue.builder().s(data.getAudioFromCustomer() == null ? "" : data.getAudioFromCustomer()).build());
        item.put("audioToCustomer", AttributeValue.builder().s(data.getAudioToCustomer() == null ? "" : data.getAudioToCustomer()).build());
        item.put("audioMixed", AttributeValue.builder().s(data.getAudioMixed() == null ? "" : data.getAudioMixed()).build());

        // Write the item to the table
        PutItemRequest request = PutItemRequest.builder()
                //.tableName(DDB_TABLE)
                .item(item)
                .build();

        ddb.putItem(request);
    }

    private void updateConnectContactAttributes(ContactTraceRecord traceRecord, ConnectAttributesData connectAttributes) {
        ConnectClient connectClient = ConnectClient.builder()
                .region(Region.of(REGION.getName()))
                .build();

        Map<String, String> attributes = new HashMap<>();
        attributes.put("audioFromCustomer", connectAttributes.getAudioFromCustomer() == null ? "" : connectAttributes.getAudioFromCustomer());
        attributes.put("audioToCustomer", connectAttributes.getAudioToCustomer() == null ? "" : connectAttributes.getAudioToCustomer());
        attributes.put("audioMixed", connectAttributes.getAudioMixed() == null ? "" : connectAttributes.getAudioMixed());

        String initialContactId = traceRecord.getInitialContactId() != null ? traceRecord.getInitialContactId() : traceRecord.getContactId();
        String instanceId = traceRecord.getInstanceARN().split("/")[1];

        logger.info(String.format("Instance ID: %s, Contact ID: %s, Initial Contact ID: %s", instanceId, traceRecord.getContactId(), traceRecord.getInitialContactId()));

        UpdateContactAttributesRequest request = UpdateContactAttributesRequest.builder()
                .attributes(attributes)
                .initialContactId(initialContactId)
                .instanceId(instanceId)
                .build();

        UpdateContactAttributesResponse response = connectClient.updateContactAttributes(request);
        if (response.sdkHttpResponse().statusCode() >= 300) {
            logger.error("Error updating contact attributes, status code: " + response.sdkHttpResponse().statusCode());
        }
    }
}
