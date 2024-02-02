package ProcessKvs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.joda.time.DateTime;

@Data
@Builder(setterPrefix = "with")
@NoArgsConstructor
@AllArgsConstructor
public class RecordingData {
    private String awsRegion;

    private int recordingAuth = 0; //0: no recording, 1: recording from customer, 2: recording to customer, 3: ALL

    private String instanceARN;
    private String initialContactId;
    private String contactId;
    private String customerNumber;
    private String languageCode;
    private String agentName;

    private String streamARN;
    private String startFragmentNum;
    private String startTimestamp;
    private String stopFragmentNumber;
    private String stopTimestamp;

    private String audioFromCustomer;
    private String audioToCustomer;
    private String audioMixed;

    private DateTime dateTime;
}
