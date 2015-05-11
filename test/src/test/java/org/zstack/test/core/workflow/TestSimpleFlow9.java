package org.zstack.test.core.workflow;

import junit.framework.Assert;
import org.junit.Test;
import org.zstack.core.workflow.*;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: frank
 * Time: 3:33 PM
 * To change this template use File | Settings | File Templates.
 */
public class TestSimpleFlow9 {
    CLogger logger = Utils.getLogger(TestSimpleFlow9.class);
    boolean success;

    @Test
    public void test() throws WorkFlowException {
        final int[] count = {0};
        int num = 10;

        SimpleFlowChain schain = new SimpleFlowChain();
        for (int i=0; i<num; i++) {
            schain.then(new Flow() {
                @Override
                public void run(FlowTrigger chain, Map data) {
                    count[0] ++;
                    chain.next();
                }

                @Override
                public void rollback(FlowTrigger chain, Map data) {
                }
            });
        }

        schain.done(new FlowDoneHandler() {
            @Override
            public void handle(Map data) {
                success = true;
            }
        }).start();

        Assert.assertEquals(num, count[0]);
        Assert.assertTrue(success);
    }
}
