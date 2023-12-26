package ProcessKvs;

import ProcessKvs.audio.AudioStreamService;
import ProcessKvs.audio.AudioUtils;
import ProcessKvs.model.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.DateTime;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Handler for requests to Lambda function.
 */
public class ProcessKvsLambda implements RequestHandler<KinesisEvent, String> {
    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    private static final String DDB_TABLE = System.getenv("DDB_TABLE");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(ProcessKvsLambda.class);

    @Override
    public String handleRequest(KinesisEvent kinesisEvent, Context context) {
        System.out.println("Processing CTR Event");

        for (KinesisEvent.KinesisEventRecord record : kinesisEvent.getRecords()) {
            try {
                String recordData = new String(record.getKinesis().getData().array());
                System.out.println("Record Data: " + recordData);
                this.processData(recordData);
            } catch (Exception e) {
                // if json does not contain required data, will exit early
                System.out.println(e.toString());
            }
        }

        return "{ \"result\": \"Success\" }";
    }

    private boolean processData(String data) {
        JSONObject json = new JSONObject(data);
        ContactTraceRecord traceRecord = new ContactTraceRecord(json);
        List<KVStreamRecordingData> recordings = traceRecord.getRecordings();

        if (recordings.isEmpty()) {
            logger.info("No Voice recording, skipped");
            return false;
        }

        if (traceRecord.getAttributes().getRecordingAuth() == -1) {
            logger.info("No valid recordingAuth, skipped");
            return false;
        }

        KVStreamRecordingData streamRecordingData = recordings.get(0);
        // Begin processing audio stream
        AudioStreamService streamingService = new AudioStreamService();

        //System.out.println(event);
        RecordingData recordingData = parseEvent(traceRecord, streamRecordingData);

        try {
            if (recordingData.getRecordingAuth() <= AudioUtils.AUTH_AUDIO_NONE) {
                logger.info("Recording is not authorized");
                saveToDdb(recordingData);
                return false;
            }
            streamingService.processAudioStream(recordingData);
            saveToDdb(recordingData);
            return true;
        } catch (Exception e) {
            logger.error("KVS to Transcribe Streaming failed with: ", e);
            return false;
        }

    }

    private RecordingData parseEvent(ContactTraceRecord traceRecord, KVStreamRecordingData recording) {

        return RecordingData.builder()
                .withAwsRegion(REGION.getName())
                .withRecordingAuth(traceRecord.getAttributes().getRecordingAuth())
                .withContactId(traceRecord.getContactId())
                .withCustomerNumber(traceRecord.getCustomerEndpoint().getAddress())
                .withLanguageCode(traceRecord.getAttributes().getLanguageCode().orElse(""))
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
                .tableName(DDB_TABLE)
                .item(item)
                .build();

        ddb.putItem(request);
    }
}
