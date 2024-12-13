package ProcessKvs.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(setterPrefix = "with")
@NoArgsConstructor
@AllArgsConstructor
public class ConnectAttributesData {
    private String audioFromCustomer;
    private String audioToCustomer;
    private String audioMixed;
}
