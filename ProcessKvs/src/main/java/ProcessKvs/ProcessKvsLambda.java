package ProcessKvs;

import ProcessKvs.audio.AudioStreamService;
import ProcessKvs.audio.AudioUtils;
import ProcessKvs.model.ConnectKvsEvent;
import ProcessKvs.model.RecordingData;
import ProcessKvs.model.ResultData;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Handler for requests to Lambda function.
 */
public class ProcessKvsLambda implements RequestStreamHandler {
    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    private static final String DDB_TABLE = System.getenv("DDB_TABLE");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(ProcessKvsLambda.class);

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        System.out.println("Processing Connect Event");

        //Configure ObjectMapper
        objectMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        ConnectKvsEvent event = objectMapper.readValue(inputStream, ConnectKvsEvent.class);

        //System.out.println(event);
        RecordingData recording = parseEvent(event);

        ResultData outData = new ResultData();
        if (recording.getRecordingAuth() <= AudioUtils.AUTH_AUDIO_NONE) {
            outData.setSuccess("false");
            outData.setMessage("Recording is not authorized");

            saveToDdb(recording);
            objectMapper.writeValue(outputStream, outData); //write to the outputStream what you want to return
            return;
        }


        AudioStreamService streamingService = new AudioStreamService();
        try {
            streamingService.processAudioStream(recording);
        } catch (Exception e) {
            logger.error("Process KVS Streaming failed with: ", e);
        }

        saveToDdb(recording);

        outData.setSuccess("true");
        outData.setMessage("Recording uploaded");
        objectMapper.writeValue(outputStream, outData); //write to the outputStream what you want to return
    }

    /**
     * converts an input stream to a string
     * @param inputStream InputStream object
     * @return String with stream contents
     */
    public static String convertStreamToString(InputStream inputStream) {
        Scanner s = new Scanner(inputStream).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private RecordingData parseEvent(ConnectKvsEvent event) {
        ConnectKvsEvent.ContactData contactData = event.getDetails().getContactData();
        ConnectKvsEvent.Audio audio = contactData.getMediaStreams().getCustomer().getAudio();

        return RecordingData.builder()
                .withAwsRegion(contactData.getAwsRegion())
                .withRecordingAuth(contactData.getAttributes().get("recordingAuth") == null ? 0 : Integer.parseInt(contactData.getAttributes().get("recordingAuth")))
                .withContactId(contactData.getContactId())
                .withCustomerNumber(contactData.getCustomerEndpoint().getAddress())
                .withLanguageCode(contactData.getLanguageCode())
                .withAgentName(contactData.getAttributes().get("agentName"))
                .withStreamARN(audio.getStreamARN())
                .withStartFragmentNum(audio.getStartFragmentNumber())
                .withStartTimestamp(audio.getStartTimestamp())
                .withStopFragmentNumber(audio.getStopFragmentNumber())
                .withStopTimestamp(audio.getStopTimestamp())
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
        item.put("awsRegion", AttributeValue.builder().s(data.getAwsRegion()).build());
        item.put("recordingAuth", AttributeValue.builder().s(String.valueOf(data.getRecordingAuth())).build());
        item.put("languageCode", AttributeValue.builder().s(data.getLanguageCode()).build());
        item.put("agentName", AttributeValue.builder().s(data.getAgentName()).build());
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
