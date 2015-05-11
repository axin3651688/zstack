package org.zstack.network.service.portforwarding;

import org.zstack.header.message.APIDeleteMessage;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;
/**
 * @api
 * delete a port forwarding rule
 *
 * @category port forwarding
 *
 * @since 0.1.0
 *
 * @cli
 *
 * @httpMsg
 * {
"org.zstack.network.service.portforwarding.APIDeletePortForwardingRuleMsg": {
"uuid": "b2e2632e5094430691ed9a7152e6e489",
"deleteMode": "Permissive",
"session": {
"uuid": "83e02bfaaffd4b88a05749d2c0f9ddde"
}
}
}
 *
 * @msg
 *
 * {
"org.zstack.network.service.portforwarding.APIDeletePortForwardingRuleMsg": {
"uuid": "b2e2632e5094430691ed9a7152e6e489",
"deleteMode": "Permissive",
"session": {
"uuid": "83e02bfaaffd4b88a05749d2c0f9ddde"
},
"timeout": 1800000,
"id": "b5a2333e1c864d7391f2db08118c1bab",
"serviceId": "api.portal"
}
}
 *
 * @result
 * see :ref:`APIDetachPortForwardingRuleEvent`
 */

public class APIDeletePortForwardingRuleMsg extends APIDeleteMessage {
    /**
     * @desc
     * rule uuid
     */
    @APIParam
    private String uuid;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
