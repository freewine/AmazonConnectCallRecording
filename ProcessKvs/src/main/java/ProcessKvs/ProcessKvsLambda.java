package ProcessKvs;

import ProcessKvs.audio.AudioStreamService;
import ProcessKvs.model.ConnectKvsEvent;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.Scanner;

/**
 * Handler for requests to Lambda function.
 */
public class ProcessKvsLambda implements RequestStreamHandler {
    private ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(ProcessKvsLambda.class);

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        System.out.println("Processing Connect Event");

        //Configure ObjectMapper
        objectMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        ConnectKvsEvent event = objectMapper.readValue(inputStream, ConnectKvsEvent.class);

        //objectMapper.writeValue(outputStream, deserializedInput); //write to the outputStream what you want to return
        System.out.println(event);

        ConnectKvsEvent.ContactData contactData = event.getDetails().getContactData();
        ConnectKvsEvent.Customer customer = event.getDetails().getContactData().getMediaStreams().getCustomer();

        AudioStreamService streamingService = new AudioStreamService();
        try {
            streamingService.processAudioStream(
                    customer.getAudio().getStreamARN(),
                    customer.getAudio().getStartFragmentNumber(),
                    contactData.getContactId(),
                    Optional.ofNullable(contactData.getLanguageCode())
            );
        } catch (Exception e) {
            logger.error("Process KVS Streaming failed with: ", e);
        }

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
}
