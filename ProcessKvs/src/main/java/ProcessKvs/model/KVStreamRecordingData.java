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
import org.json.JSONObject;

@Getter
public class KVStreamRecordingData {
    private final String fragmentStartNumber;
    private final String fragmentStopNumber;
    private final String location;
    private final String startTimestamp;
    private final String stopTimestamp;
    private final String storageType;

    public KVStreamRecordingData(JSONObject jsonObject) {
        this.fragmentStartNumber = jsonObject.isNull("FragmentStartNumber") ? null : jsonObject.getString("FragmentStartNumber");
        this.fragmentStopNumber = jsonObject.isNull("FragmentStopNumber") ? null : jsonObject.getString("FragmentStopNumber");
        this.location = jsonObject.isNull("Location") ? null : jsonObject.getString("Location");
        this.startTimestamp = jsonObject.isNull("StartTimestamp") ? null : jsonObject.getString("StartTimestamp");
        this.stopTimestamp = jsonObject.isNull("StopTimestamp") ? null : jsonObject.getString("StopTimestamp");
        this.storageType = jsonObject.isNull("StorageType") ? null : jsonObject.getString("StorageType");
    }
}
