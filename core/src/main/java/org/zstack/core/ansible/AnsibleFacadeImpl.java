package org.zstack.core.ansible;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.DirectoryWalker;
import org.apache.commons.io.FileUtils;
import org.ini4j.Wini;
import org.springframework.beans.factory.annotation.Autowired;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.SyncTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.header.AbstractService;
import org.zstack.header.core.Completion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.message.Message;
import org.zstack.utils.DebugUtils;
import org.zstack.utils.ShellUtils;
import org.zstack.utils.ShellUtils.ShellException;
import org.zstack.utils.StringDSL;
import org.zstack.utils.Utils;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 */
public class AnsibleFacadeImpl extends AbstractService implements AnsibleFacade {
    private static final CLogger logger = Utils.getLogger(AnsibleFacadeImpl.class);

    private int maxForks = 100;
    private String filesDir = PathUtil.join(AnsibleConstant.ROOT_DIR, "files");
    private Map<String, Boolean> moduleChanges = new HashMap<String, Boolean>();
    private Map<String, String> variables = new HashMap<String, String>();

    @Autowired
    private CloudBus bus;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private ThreadFacade thdf;

    void init() {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            logger.debug(String.format("skip AnsibleFacade init as it's unittest"));
            return;
        }

        File privKeyFile = PathUtil.findFileOnClassPath(AnsibleConstant.RSA_PRIVATE_KEY, true);
        ShellUtils.run(String.format("chmod 600 %s", privKeyFile.getAbsolutePath()));

        try {
            File invFile = new File(AnsibleConstant.CONFIGURATION_FILE);
            File invDir = new File(invFile.getParent());
            if (!invDir.exists()) {
                invDir.mkdirs();
            }

            if (!invFile.exists()) {
                invFile.createNewFile();
            }
            Wini ini = new Wini(invFile);
            Map<String, String> cfgs = Platform.getGlobalPropertiesStartWith("Ansible.cfg.");
            ini.put("defaults", "forks", maxForks);
            ini.put("defaults", "inventory", AnsibleConstant.INVENTORY_FILE);
            for (Map.Entry<String, String> e : cfgs.entrySet()) {
                String key = StringDSL.stripStart(e.getKey(), "Ansible.cfg.");
                ini.put("defaults", key, e.getValue());
                logger.debug(String.format("added ansible cfg[%s=%s] to %s", key, e.getValue(), AnsibleConstant.CONFIGURATION_FILE));
            }
            ini.store();

            Map<String, String> vars = Platform.getGlobalPropertiesStartWith("Ansible.var.");
            for (Map.Entry<String, String> e : vars.entrySet()) {
                String key = StringDSL.stripStart(e.getKey(), "Ansible.var.");
                variables.put(key, e.getValue());
                logger.debug(String.format("discovered ansible variable[%s=%s]", key, e.getValue()));
            }

            deployModule("ansible/zstacklib", "zstacklib.yaml");
        } catch (IOException e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof RunAnsibleMsg) {
            handle((RunAnsibleMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(final RunAnsibleMsg msg) {
        thdf.syncSubmit(new SyncTask<Object>() {
            @Override
            public String getSyncSignature() {
                return String.format("run-anisble-for-host-%s", msg.getTargetIp());
            }

            @Override
            public int getSyncLevel() {
                return 1;
            }

            @Override
            public String getName() {
                return getSyncSignature();
            }

            private void run(Completion completion) {
                logger.debug(String.format("start running ansible for playbook[%s]", msg.getPlayBookName()));
                Map<String, Object> arguments = new HashMap<String, Object>();
                if (msg.getArguments() != null) {
                    arguments.putAll(msg.getArguments());
                }
                arguments.put("host", msg.getTargetIp());
                arguments.put("zstack_root", AnsibleGlobalProperty.ZSTACK_ROOT);
                arguments.put("pkg_zstacklib", AnsibleGlobalProperty.ZSTACKLIB_PACKAGE_NAME);
                arguments.putAll(getVariables());
                String playBookPath = PathUtil.join(AnsibleConstant.ROOT_DIR, msg.getPlayBookName());
                try {
                    if (AnsibleGlobalProperty.DEBUG_MODE2) {
                        ShellUtils.run(String.format("%s %s -i %s -vvvv --private-key %s -e '%s' | tee -a %s",
                                AnsibleGlobalProperty.EXECUTABLE, playBookPath, AnsibleConstant.INVENTORY_FILE, msg.getPrivateKeyFile(), JSONObjectUtil.toJsonString(arguments), AnsibleConstant.LOG_PATH),
                                AnsibleConstant.ROOT_DIR);
                    } else if (AnsibleGlobalProperty.DEBUG_MODE) {
                        ShellUtils.run(String.format("%s %s -i %s -vvvv --private-key %s -e '%s'",
                                AnsibleGlobalProperty.EXECUTABLE, playBookPath, AnsibleConstant.INVENTORY_FILE, msg.getPrivateKeyFile(), JSONObjectUtil.toJsonString(arguments)),
                                AnsibleConstant.ROOT_DIR);
                    } else {
                        ShellUtils.run(String.format("%s %s -i %s --private-key %s -e '%s'",
                                AnsibleGlobalProperty.EXECUTABLE, playBookPath, AnsibleConstant.INVENTORY_FILE, msg.getPrivateKeyFile(), JSONObjectUtil.toJsonString(arguments)),
                                AnsibleConstant.ROOT_DIR);
                    }
                } catch (ShellException se) {
                    logger.warn(se.getMessage(), se);
                    throw new OperationFailureException(errf.stringToOperationError(se.getMessage()));
                }

                completion.success();
            }

            @Override
            public Object call() throws Exception {
                final RunAnsibleReply reply = new RunAnsibleReply();
                run(new Completion(msg) {
                    @Override
                    public void success() {
                        bus.reply(msg, reply);
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        reply.setError(errorCode);
                        bus.reply(msg, reply);
                    }
                });

                return null;
            }
        });
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(AnsibleConstant.SERVICE_ID);
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @SuppressWarnings("rawtypes")
    public class ModuleWalker extends DirectoryWalker {
        @Override
        protected void handleFile(File file, int depth, Collection results) {
            results.add(file);
        }

        public void doWalk(File start, Collection result) throws IOException {
            walk(start, result);
        }
    }

    private boolean isNeedToDeploy(String moduleName, String modulePath) throws IOException {
        boolean needed = false;

        try {
            String destModulePath = PathUtil.join(filesDir, moduleName);
            File dest = new File(destModulePath);
            if (!dest.exists()) {
                logger.debug(String.format("%s is not existing, need to deploy ansible module[%s]", destModulePath, moduleName));
                needed = true;
                return needed;
            }
            List<File> destFiles = new ArrayList<File>(20);
            ModuleWalker walker = new ModuleWalker();
            walker.doWalk(dest, destFiles);

            List<File> srcFiles = new ArrayList<File>(20);
            walker = new ModuleWalker();
            File src = new File(modulePath);
            walker.doWalk(src, srcFiles);
            if (srcFiles.size() != destFiles.size()) {
                logger.debug(String.format("%s has %s files, %s has %s files, need to deploy ansible module[%s]", destModulePath, destFiles.size(), modulePath,
                        srcFiles.size(), moduleName));
                needed = true;
                return needed;
            }

            Map<String, String> srcMd5sum = new HashMap<String, String>(srcFiles.size());
            for (File f : srcFiles) {
                FileInputStream fis = new FileInputStream(f);
                String md5 = DigestUtils.md5Hex(fis);
                srcMd5sum.put(f.getName(), md5);
            }
            Map<String, String> destMd5sum = new HashMap<String, String>(destFiles.size());
            for (File f : destFiles) {
                FileInputStream fis = new FileInputStream(f);
                String md5 = DigestUtils.md5Hex(fis);
                destMd5sum.put(f.getName(), md5);
            }
            for (Map.Entry<String, String> srcEntry : srcMd5sum.entrySet()) {
                String name = srcEntry.getKey();
                String destMd5 = destMd5sum.get(name);
                if (destMd5 == null) {
                    logger.debug(String.format("%s is not existing in %s, need to deploy ansible module[%s]", name, destModulePath, moduleName));
                    needed = true;
                    return needed;
                }
                if (!destMd5.equals(srcEntry.getValue())) {
                    logger.debug(String.format("%s's md5 changed[{%s} in %s, {%s} in %s], need to deploy ansible module[%s]", name, destMd5, destModulePath,
                            srcEntry.getValue(), modulePath, moduleName));
                    needed = true;
                    return needed;
                }
            }
            logger.debug(String.format("no file changed in ansible module[%s], no need to deploy", moduleName));
            needed = false;
            return needed;
        }  finally {
            moduleChanges.put(moduleName, needed);
        }
    }

    @Override
    public void deployModule(String modulePath, String playBookName) {
        File src = PathUtil.findFolderOnClassPath(modulePath, true);
        if (!src.isDirectory()) {
            throw new CloudRuntimeException(String.format("Cannot find ansible module[%s], it's either not existing or not a directory", modulePath));
        }

        String moduleName = src.getName();

        File root = new File(AnsibleConstant.ROOT_DIR);
        if (!root.exists()) {
            root.mkdirs();
        }

        File filesRoot = new File(filesDir);
        if (!filesRoot.exists()) {
            filesRoot.mkdirs();
        }

        try {
            if (!isNeedToDeploy(moduleName, src.getAbsolutePath())) {
                return;
            }

            String destModulePath = PathUtil.join(filesRoot.getAbsolutePath(), moduleName);
            File dest = new File(destModulePath);
            if (dest.exists()) {
                FileUtils.forceDelete(dest);
                FileUtils.forceMkdir(dest);
            }
            FileUtils.copyDirectory(src, dest);

            boolean isPlaybookLinked = false;
            for (File f : dest.listFiles()) {
                if (f.getName().equals(playBookName)) {
                    String lnPath = PathUtil.join(AnsibleConstant.ROOT_DIR, playBookName);
                    File lnFile = new File(lnPath);
                    if (lnFile.exists()) {
                        lnFile.delete();
                    }
                    Files.createSymbolicLink(Paths.get(lnPath), Paths.get(f.getAbsolutePath()));
                    isPlaybookLinked = true;
                }
            }

            if (!isPlaybookLinked) {
                throw new IllegalArgumentException(String.format("cannot find playbook[%s] in module[%s], module files are%s", playBookName, modulePath, Arrays.asList(src.list())));
            }

            logger.debug(String.format("successfully deployed ansible module[%s]", modulePath));
        } catch (Exception e) {
            String err = String.format("Unable to deploy ansible module[%s] from %s", moduleName, modulePath);
            throw new CloudRuntimeException(err, e);
        }
    }

    @Override
    public boolean isModuleChanged(String playbookName) {
        String moduleName = StringDSL.stripEnd(playbookName, ".yaml");
        Boolean ret = moduleChanges.get(moduleName);
        DebugUtils.Assert(ret != null, String.format("cannot find ansible module name[%s]", moduleName));
        if (ret) {
            // we only need to deploy once
            moduleChanges.put(moduleName, false);
        }
        return ret;
    }

    @Override
    public Map<String, String> getVariables() {
        return variables;
    }
}
