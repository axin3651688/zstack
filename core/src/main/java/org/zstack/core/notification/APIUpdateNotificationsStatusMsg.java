package org.zstack.core.notification;

import org.springframework.http.HttpMethod;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.rest.RestRequest;

import java.util.List;

/**
 * Created by xing5 on 2017/3/18.
 */
@RestRequest(
        path = "/notifications/actions",
        method = HttpMethod.POST,
        isAction = true,
        responseClass = APIQueryNotificationReply.class
)
public class APIUpdateNotificationsStatusMsg extends APIMessage {
    @APIParam(nonempty = true)
    private List<String> uuids;
    @APIParam(validValues = {"Unread", "Read"})
    private String status;

    public List<String> getUuids() {
        return uuids;
    }

    public void setUuids(List<String> uuids) {
        this.uuids = uuids;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}