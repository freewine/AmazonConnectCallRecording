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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for requests to Lambda function.
 */
public class ProcessKvsLambda implements RequestHandler<KinesisEvent, String> {
    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    private static final Logger logger = LoggerFactory.getLogger(ProcessKvsLambda.class);

    @Override
    public String handleRequest(KinesisEvent kinesisEvent, Context context) {
        System.out.println("Processing CTR Event");

        for (KinesisEvent.KinesisEventRecord record : kinesisEvent.getRecords()) {
            try {
                String recordData = new String(record.getKinesis().getData().array());
                System.out.println("Record Data: " + recordData);
                processCTR(recordData);
            } catch (Exception e) {
                // if json does not contain required data, will exit early
                System.out.println(e.toString());
            }
        }

        return "{ \"result\": \"Success\" }";
    }

    private void processCTR(String ctrStr) {
        JSONObject json = new JSONObject(ctrStr);
        ContactTraceRecord traceRecord = new ContactTraceRecord(json);
        List<KVStreamRecordingData> recordings = traceRecord.getRecordings();

        if(!traceRecord.getChannel().equals("VOICE"))
        {
            logger.info("Not Voice channel, skipped");
            return;
        }

        if (recordings.isEmpty()) {
            logger.info("No Voice recording, skipped");
            return;
        }

        int recordingAuth = traceRecord.getAttributes().getRecordingAuth();
        if ((recordingAuth <= AudioUtils.AUTH_AUDIO_NONE || recordingAuth > AudioUtils.AUTH_AUDIO_MIXED)) {
            logger.info("Recording is not authorized, skipped. recordingAuth:" + recordingAuth);
            return;
        }

        if (traceRecord.getAttributes().hasRecordingAttributes()) {
            logger.info("Recording Attributes existed, skipped.");
            return;
        }

        ConnectAttributesData connectAttributes = new ConnectAttributesData();

        //A CTR may include multi recordings, event multi types of recordings, we only process the type of KINESIS_VIDEO_STREAM
        for (KVStreamRecordingData recording : recordings) {
            if (!recording.getStorageType().equals("KINESIS_VIDEO_STREAM")) {
                logger.info("Recording StorageType is not KINESIS_VIDEO_STREAM, skipped. StorageType:" + recording.getStorageType());
                continue;
            }

            logger.info("Recording StorageType is KINESIS_VIDEO_STREAM, recording processing started");

            //System.out.println(event);
            RecordingData recordingData = extractRecordingData(traceRecord, recording);

            // Begin processing audio stream
            AudioStreamService streamingService = new AudioStreamService();
            try {
                streamingService.processAudioStream(recordingData);

                logger.info(String.format("fromCustomer: %s, toCustomer: %s, mixed: %s", recordingData.getAudioFromCustomer(), recordingData.getAudioToCustomer(), recordingData.getAudioMixed()));
                //append audio file path to connect attributes
                if (recordingData.getAudioFromCustomer() != null && (!recordingData.getAudioFromCustomer().isEmpty())) {
                    if (connectAttributes.getAudioFromCustomer() == null || connectAttributes.getAudioFromCustomer().isEmpty()) {
                        connectAttributes.setAudioFromCustomer(recordingData.getAudioFromCustomer());
                    } else {
                        connectAttributes.setAudioFromCustomer(connectAttributes.getAudioFromCustomer() + ", " + recordingData.getAudioFromCustomer());
                    }
                }

                if (recordingData.getAudioToCustomer() != null && (!recordingData.getAudioToCustomer().isEmpty())) {
                    if (connectAttributes.getAudioToCustomer() == null || connectAttributes.getAudioToCustomer().isEmpty()) {
                        connectAttributes.setAudioToCustomer(recordingData.getAudioToCustomer());
                    } else {
                        connectAttributes.setAudioToCustomer(connectAttributes.getAudioToCustomer() + ", " + recordingData.getAudioToCustomer());
                    }
                }

                if (recordingData.getAudioMixed() != null && (!recordingData.getAudioMixed().isEmpty())) {
                    if (connectAttributes.getAudioMixed() == null || connectAttributes.getAudioMixed().isEmpty()) {
                        connectAttributes.setAudioMixed(recordingData.getAudioMixed());
                    } else {
                        connectAttributes.setAudioMixed(connectAttributes.getAudioMixed() + ", " + recordingData.getAudioMixed());
                    }
                }
            } catch (Exception e) {
                logger.error("KVS processing failed with: ", e);
            }

            logger.info("recording processing finished");

        }
        updateConnectContactAttributes(traceRecord, connectAttributes);
    }

    private RecordingData extractRecordingData(ContactTraceRecord traceRecord, KVStreamRecordingData recording) {

        return RecordingData.builder()
                .withAwsRegion(REGION.getName())
                .withRecordingAuth(traceRecord.getAttributes().getRecordingAuth())
                .withContactId(traceRecord.getContactId())
                .withInitialContactId(traceRecord.getInitialContactId())
                .withInitiationTimestamp(traceRecord.getInitiationTimestamp())
                .withInstanceARN(traceRecord.getInstanceARN())
                .withCustomerNumber(traceRecord.getCustomerEndpoint().getAddress())
                .withLanguageCode(traceRecord.getAttributes().getLanguageCode())
                .withStreamARN(recording.getLocation())
                .withStartFragmentNum(recording.getFragmentStartNumber())
                .withStartTimestamp(recording.getStartTimestamp())
                .withStopFragmentNumber(recording.getFragmentStopNumber())
                .withStopTimestamp(recording.getStopTimestamp())
                .withDateTime(new DateTime())
                .build();
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
