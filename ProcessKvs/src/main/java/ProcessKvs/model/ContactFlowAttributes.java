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
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONObject;

import java.util.Optional;

@Getter
public class ContactFlowAttributes {
    private int recordingAuth = -1;

    //default "en-US"
    private String languageCode = "en-US";

    public ContactFlowAttributes(JSONObject jsonObject) {
        if (jsonObject.has("recordingAuth")) {
            this.recordingAuth = NumberUtils.toInt(jsonObject.getString("recordingAuth"), -1);
        }

        if (jsonObject.has("languageCode")) {
            this.languageCode = jsonObject.getString("languageCode");
        }
    }

}
