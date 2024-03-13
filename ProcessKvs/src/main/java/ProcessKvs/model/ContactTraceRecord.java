/******************************************************************************
 *  Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved. 
 *  Licensed under the Apache License Version 2.0 (the 'License'). You may not
 *  use this file except in compliance with the License. A copy of the License
 *  is located at                                                            
 *
 *      http://www.apache.org/licenses/                                        
 *  or in the 'license' file accompanying this file. This file is distributed on
 *  an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or
 *  implied. See the License for the specific language governing permissions and
 *  limitations under the License.                                              
 ******************************************************************************/

package ProcessKvs.model;

import lombok.Getter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@Getter
public class ContactTraceRecord {

    private final String instanceARN;
    private final String contactId;
    private final String channel;
    private final String initialContactId;
    private final String initiationTimestamp;
    private final CustomerEndpoint customerEndpoint;
    private final ContactFlowAttributes attributes;
    private final List<KVStreamRecordingData> recordings = new ArrayList<>();

    public ContactTraceRecord(JSONObject jsonObject) {
        this.instanceARN = jsonObject.getString("InstanceARN");
        this.contactId = jsonObject.getString("ContactId");
        this.channel = jsonObject.getString("Channel");
        this.initialContactId = jsonObject.isNull("InitialContactId") ? null : jsonObject.getString("InitialContactId");
        this.initiationTimestamp = jsonObject.getString("InitiationTimestamp");
        this.customerEndpoint = new CustomerEndpoint(jsonObject.getJSONObject("CustomerEndpoint"));
        this.attributes = new ContactFlowAttributes(jsonObject.getJSONObject("Attributes"));
        JSONArray recordings = jsonObject.isNull("Recordings") ? null : jsonObject.getJSONArray("Recordings");
        for (int i = 0; i < (recordings != null ? recordings.length() : 0); i++) {
            this.recordings.add(new KVStreamRecordingData(recordings.getJSONObject(i)));
        }
    }

    public boolean hasRecordings() {
        return !recordings.isEmpty();
    }

}
