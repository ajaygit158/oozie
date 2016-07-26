/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oozie.action.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Permission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

public class LauncherAM {

    static final String CONF_OOZIE_ACTION_MAIN_CLASS = "oozie.launcher.action.main.class";

    static final String ACTION_PREFIX = "oozie.action.";
    public static final String CONF_OOZIE_ACTION_MAX_OUTPUT_DATA = ACTION_PREFIX + "max.output.data";
    static final String CONF_OOZIE_ACTION_MAIN_ARG_PREFIX = ACTION_PREFIX + "main.arg.";
    static final String CONF_OOZIE_ACTION_MAIN_ARG_COUNT = ACTION_PREFIX + CONF_OOZIE_ACTION_MAIN_ARG_PREFIX + "count";
    static final String CONF_OOZIE_EXTERNAL_STATS_MAX_SIZE = "oozie.external.stats.max.size";

    static final String OOZIE_ACTION_DIR_PATH = ACTION_PREFIX + "dir.path";
    static final String ACTION_PREPARE_XML = ACTION_PREFIX + "prepare.xml";
    static final String ACTION_DATA_SEQUENCE_FILE = "action-data.seq"; // COMBO FILE
    static final String ACTION_DATA_EXTERNAL_CHILD_IDS = "externalChildIDs";
    static final String ACTION_DATA_OUTPUT_PROPS = "output.properties";
    static final String ACTION_DATA_STATS = "stats.properties";
    static final String ACTION_DATA_NEW_ID = "newId";
    static final String ACTION_DATA_ERROR_PROPS = "error.properties";

    // TODO: OYA: more unique file names?  action.xml may be stuck for backwards compat though
    public static final String LAUNCHER_JOB_CONF_XML = "launcher.xml";
    public static final String ACTION_CONF_XML = "action.xml";
    public static final String ACTION_DATA_FINAL_STATUS = "final.status";

    private static AMRMClientAsync<AMRMClient.ContainerRequest> amRmClientAsync = null;
    private static Configuration launcherJobConf = null;
    private static Path actionDir;
    private static Map<String, String> actionData = new HashMap<String,String>();

    private static void printDebugInfo(String[] mainArgs) throws IOException {
        printContentsOfCurrentDir();

        System.out.println();
        System.out.println("Oozie Launcher Application Master configuration");
        System.out.println("===============================================");
        System.out.println("Workflow job id   : " + launcherJobConf.get("oozie.job.id"));
        System.out.println("Workflow action id: " + launcherJobConf.get("oozie.action.id"));
        System.out.println();
        System.out.println("Classpath         :");
        System.out.println("------------------------");
        StringTokenizer st = new StringTokenizer(System.getProperty("java.class.path"), ":");
        while (st.hasMoreTokens()) {
            System.out.println("  " + st.nextToken());
        }
        System.out.println("------------------------");
        System.out.println();
        String mainClass = launcherJobConf.get(CONF_OOZIE_ACTION_MAIN_CLASS);
        System.out.println("Main class        : " + mainClass);
        System.out.println();
        System.out.println("Maximum output    : "
                + launcherJobConf.getInt(CONF_OOZIE_ACTION_MAX_OUTPUT_DATA, 2 * 1024));
        System.out.println();
        System.out.println("Arguments         :");
        for (String arg : mainArgs) {
            System.out.println("                    " + arg);
        }

        System.out.println();
        System.out.println("Java System Properties:");
        System.out.println("------------------------");
        System.getProperties().store(System.out, "");
        System.out.flush();
        System.out.println("------------------------");
        System.out.println();

        System.out.println("=================================================================");
        System.out.println();
        System.out.println(">>> Invoking Main class now >>>");
        System.out.println();
        System.out.flush();
    }

    // TODO: OYA: delete me when making real Action Mains
    public static class DummyMain {
        public static void main(String[] args) throws Exception {
            System.out.println("Hello World!");
            if (launcherJobConf.get("foo", "0").equals("1")) {
                throw new IOException("foo 1");
            } else if (launcherJobConf.get("foo", "0").equals("2")) {
                throw new JavaMainException(new IOException("foo 2"));
            } else if (launcherJobConf.get("foo", "0").equals("3")) {
                throw new LauncherMainException(3);
            } else if (launcherJobConf.get("foo", "0").equals("4")) {
                System.exit(0);
            } else if (launcherJobConf.get("foo", "0").equals("5")) {
                System.exit(1);
            }
        }
    }

    // TODO: OYA: rethink all print messages and formatting
    public static void main(String[] AMargs) throws Exception {
        ErrorHolder eHolder = new ErrorHolder();
        FinalApplicationStatus finalStatus = FinalApplicationStatus.FAILED;
        try {
            try {
                launcherJobConf = readLauncherConf();
                System.out.println("Launcher AM configuration loaded");
            } catch (Exception ex) {
                eHolder.setErrorMessage("Could not load the Launcher AM configuration file");
                eHolder.setErrorCause(ex);
                throw ex;
            }

            registerWithRM();

            actionDir = new Path(launcherJobConf.get(OOZIE_ACTION_DIR_PATH));

            try {
                System.out.println("\nStarting the execution of prepare actions");
                executePrepare();
                System.out.println("Completed the execution of prepare actions successfully");
            } catch (Exception ex) {
                eHolder.setErrorMessage("Prepare execution in the Launcher AM has failed");
                eHolder.setErrorCause(ex);
                throw ex;
            }

            String[] mainArgs = getMainArguments(launcherJobConf);

            // TODO: OYA: should we allow turning this off?
            // TODO: OYA: what should default be?
            if (launcherJobConf.getBoolean("oozie.launcher.print.debug.info", true)) {
                printDebugInfo(mainArgs);
            }
            finalStatus = runActionMain(mainArgs, eHolder);
            if (finalStatus != FinalApplicationStatus.SUCCEEDED) {
                handleActionData();
                if (actionData.get(ACTION_DATA_OUTPUT_PROPS) != null) {
                    System.out.println();
                    System.out.println("Oozie Launcher, capturing output data:");
                    System.out.println("=======================");
                    System.out.println(actionData.get(ACTION_DATA_OUTPUT_PROPS));
                    System.out.println();
                    System.out.println("=======================");
                    System.out.println();
                }
                if (actionData.get(ACTION_DATA_NEW_ID) != null) {
                    System.out.println();
                    System.out.println("Oozie Launcher, propagating new Hadoop job id to Oozie");
                    System.out.println("=======================");
                    System.out.println(actionData.get(ACTION_DATA_NEW_ID));
                    System.out.println("=======================");
                    System.out.println();
                }
            }
        } finally {
            try {
                // Store final status in case Launcher AM falls off the RM
                actionData.put(ACTION_DATA_FINAL_STATUS, finalStatus.toString());
                if (finalStatus != FinalApplicationStatus.SUCCEEDED) {
                    failLauncher(eHolder);
                }
                uploadActionDataToHDFS();
            } finally {
                try {
                    unregisterWithRM(finalStatus, eHolder.getErrorMessage());
                } finally {
                    LauncherAMCallbackNotifier cn = new LauncherAMCallbackNotifier(launcherJobConf);
                    cn.notifyURL(finalStatus);
                }
            }
        }
    }

    private static void registerWithRM() throws IOException, YarnException {
        AMRMClient<AMRMClient.ContainerRequest> amRmClient = AMRMClient.createAMRMClient();

        AMRMCallBackHandler callBackHandler = new AMRMCallBackHandler();
        // TODO: OYA: make heartbeat interval configurable
        // TODO: OYA: make heartbeat interval higher to put less load on RM, but lower than timeout
        amRmClientAsync = AMRMClientAsync.createAMRMClientAsync(amRmClient, 60000, callBackHandler);
        amRmClientAsync.init(launcherJobConf);
        amRmClientAsync.start();

        // hostname and tracking url are determined automatically
        amRmClientAsync.registerApplicationMaster("", 0, "");
    }

    private static void unregisterWithRM(FinalApplicationStatus status, String message) throws YarnException, IOException {
        if (amRmClientAsync != null) {
            System.out.println("Stopping AM");
            try {
                message = (message == null) ? "" : message;
                // tracking url is determined automatically
                amRmClientAsync.unregisterApplicationMaster(status, message, "");
            } catch (YarnException ex) {
                System.err.println("Error un-registering AM client");
                throw ex;
            } catch (IOException ex) {
                System.err.println("Error un-registering AM client");
                throw ex;
            } finally {
                amRmClientAsync.stop();
                amRmClientAsync = null;
            }
        }
    }

    // Method to execute the prepare actions
    private static void executePrepare() throws IOException, LauncherException, ParserConfigurationException, SAXException {
        String prepareXML = launcherJobConf.get(ACTION_PREPARE_XML);
        if (prepareXML != null) {
            if (prepareXML.length() != 0) {
                Configuration actionConf = new Configuration(launcherJobConf);
                actionConf.addResource(ACTION_CONF_XML);
                PrepareActionsDriver.doOperations(prepareXML, actionConf);
            } else {
                System.out.println("There are no prepare actions to execute.");
            }
        }
    }

    private static FinalApplicationStatus runActionMain(String[] mainArgs, ErrorHolder eHolder) {
        FinalApplicationStatus finalStatus = FinalApplicationStatus.FAILED;
        LauncherSecurityManager secMan = new LauncherSecurityManager();
        try {
            Class<?> klass = launcherJobConf.getClass(CONF_OOZIE_ACTION_MAIN_CLASS, Object.class);
            Method mainMethod = klass.getMethod("main", String[].class);
            // Enable LauncherSecurityManager to catch System.exit calls
            secMan.set();
            // TODO: OYA: remove this line to actually run the Main class instead of this dummy
            mainMethod = DummyMain.class.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) mainArgs);

            System.out.println();
            System.out.println("<<< Invocation of Main class completed <<<");
            System.out.println();
            finalStatus = FinalApplicationStatus.SUCCEEDED;
        } catch (InvocationTargetException ex) {
            // Get what actually caused the exception
            Throwable cause = ex.getCause();
            // If we got a JavaMainException from JavaMain, then we need to unwrap it
            if (JavaMainException.class.isInstance(cause)) {
                cause = cause.getCause();
            }
            if (LauncherMainException.class.isInstance(cause)) {
                String mainClass = launcherJobConf.get(CONF_OOZIE_ACTION_MAIN_CLASS);
                eHolder.setErrorMessage("Main Class [" + mainClass + "], exit code [" +
                        ((LauncherMainException) ex.getCause()).getErrorCode() + "]");
            } else if (SecurityException.class.isInstance(cause)) {
                if (secMan.getExitInvoked()) {
                    System.out.println("Intercepting System.exit(" + secMan.getExitCode()
                            + ")");
                    System.err.println("Intercepting System.exit(" + secMan.getExitCode()
                            + ")");
                    // if 0 main() method finished successfully
                    // ignoring
                    eHolder.setErrorCode(secMan.getExitCode());
                    if (eHolder.getErrorCode() != 0) {
                        String mainClass = launcherJobConf.get(CONF_OOZIE_ACTION_MAIN_CLASS);
                        eHolder.setErrorMessage("Main Class [" + mainClass + "], exit code [" + eHolder.getErrorCode() + "]");
                    } else {
                        finalStatus = FinalApplicationStatus.SUCCEEDED;
                    }
                }
            } else {
                eHolder.setErrorMessage(cause.getMessage());
                eHolder.setErrorCause(cause);
            }
        } catch (Throwable t) {
            eHolder.setErrorMessage(t.getMessage());
            eHolder.setErrorCause(t);
        } finally {
            // Disable LauncherSecurityManager
            secMan.unset();
        }
        return finalStatus;
    }

    private static void handleActionData() throws IOException {
        // external child IDs
        String externalChildIdsProp = System.getProperty(ACTION_PREFIX
                + ACTION_DATA_EXTERNAL_CHILD_IDS);
        if (externalChildIdsProp != null) {
            File externalChildIDs = new File(externalChildIdsProp);
            if (externalChildIDs.exists()) {
                actionData.put(ACTION_DATA_EXTERNAL_CHILD_IDS, getLocalFileContentStr(externalChildIDs, "", -1));
            }
        }

        // external stats
        String statsProp = System.getProperty(ACTION_PREFIX + ACTION_DATA_STATS);
        if (statsProp != null) {
            File actionStatsData = new File(statsProp);
            if (actionStatsData.exists()) {
                int statsMaxOutputData = launcherJobConf.getInt(CONF_OOZIE_EXTERNAL_STATS_MAX_SIZE,
                        Integer.MAX_VALUE);
                actionData.put(ACTION_DATA_STATS,
                        getLocalFileContentStr(actionStatsData, "Stats", statsMaxOutputData));
            }
        }

        // output data
        String outputProp = System.getProperty(ACTION_PREFIX + ACTION_DATA_OUTPUT_PROPS);
        if (outputProp != null) {
            File actionOutputData = new File(outputProp);
            if (actionOutputData.exists()) {
                int maxOutputData = launcherJobConf.getInt(CONF_OOZIE_ACTION_MAX_OUTPUT_DATA, 2 * 1024);
                actionData.put(ACTION_DATA_OUTPUT_PROPS,
                        getLocalFileContentStr(actionOutputData, "Output", maxOutputData));
            }
        }

        // id swap
        String newIdProp = System.getProperty(ACTION_PREFIX + ACTION_DATA_NEW_ID);
        if (newIdProp != null) {
            File newId = new File(newIdProp);
            if (newId.exists()) {
                actionData.put(ACTION_DATA_NEW_ID, getLocalFileContentStr(newId, "", -1));
            }
        }
    }

    public static String getLocalFileContentStr(File file, String type, int maxLen) throws IOException {
        StringBuilder sb = new StringBuilder();
        Reader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            char[] buffer = new char[2048];
            int read;
            int count = 0;
            while ((read = reader.read(buffer)) > -1) {
                count += read;
                if (maxLen > -1 && count > maxLen) {
                    throw new IOException(type + " data exceeds its limit [" + maxLen + "]");
                }
                sb.append(buffer, 0, read);
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return sb.toString();
    }

    private static void uploadActionDataToHDFS() throws IOException {
        Path finalPath = new Path(actionDir, ACTION_DATA_SEQUENCE_FILE);
        FileSystem fs = FileSystem.get(finalPath.toUri(), launcherJobConf);
        // upload into sequence file
        System.out.println("Oozie Launcher, uploading action data to HDFS sequence file: "
                + new Path(actionDir, ACTION_DATA_SEQUENCE_FILE).toUri());

        SequenceFile.Writer wr = null;
        try {
            wr = SequenceFile.createWriter(launcherJobConf,
                    SequenceFile.Writer.file(finalPath),
                    SequenceFile.Writer.keyClass(Text.class),
                    SequenceFile.Writer.valueClass(Text.class));
            if (wr != null) {
                Set<String> keys = actionData.keySet();
                for (String propsKey : keys) {
                    wr.append(new Text(propsKey), new Text(actionData.get(propsKey)));
                }
            }
            else {
                throw new IOException("SequenceFile.Writer is null for " + finalPath);
            }
        }
        catch(IOException e) {
            e.printStackTrace();
            throw e;
        }
        finally {
            if (wr != null) {
                wr.close();
            }
        }
    }
    private static void failLauncher(int errorCode, String message, Throwable ex) {
        ErrorHolder eHolder = new ErrorHolder();
        eHolder.setErrorCode(errorCode);
        eHolder.setErrorMessage(message);
        eHolder.setErrorCause(ex);
        failLauncher(eHolder);
    }

    private static void failLauncher(ErrorHolder eHolder) {
        if (eHolder.getErrorCause() != null) {
            eHolder.setErrorMessage(eHolder.getErrorMessage() + ", " + eHolder.getErrorCause().getMessage());
        }
        Properties errorProps = new Properties();
        errorProps.setProperty("error.code", Integer.toString(eHolder.getErrorCode()));
        errorProps.setProperty("error.reason", eHolder.getErrorMessage());
        if (eHolder.getErrorCause() != null) {
            if (eHolder.getErrorCause().getMessage() != null) {
                errorProps.setProperty("exception.message", eHolder.getErrorCause().getMessage());
            }
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            eHolder.getErrorCause().printStackTrace(pw);
            pw.close();
            errorProps.setProperty("exception.stacktrace", sw.toString());
        }
        StringWriter sw = new StringWriter();
        try {
            errorProps.store(sw, "");
            sw.close();
            actionData.put(ACTION_DATA_ERROR_PROPS, sw.toString());

            // external child IDs
            String externalChildIdsProp = System.getProperty(ACTION_PREFIX + ACTION_DATA_EXTERNAL_CHILD_IDS);
            if (externalChildIdsProp != null) {
                File externalChildIDs = new File(externalChildIdsProp);
                if (externalChildIDs.exists()) {
                    actionData.put(ACTION_DATA_EXTERNAL_CHILD_IDS, getLocalFileContentStr(externalChildIDs, "", -1));
                }
            }
        } catch (IOException ioe) {
            System.err.println("A problem occured trying to fail the launcher");
            ioe.printStackTrace();
        } finally {
            System.out.print("Failing Oozie Launcher, " + eHolder.getErrorMessage() + "\n");
            System.err.print("Failing Oozie Launcher, " + eHolder.getErrorMessage() + "\n");
            if (eHolder.getErrorCause() != null) {
                eHolder.getErrorCause().printStackTrace(System.out);
                eHolder.getErrorCause().printStackTrace(System.err);
            }
        }
    }

    private static class AMRMCallBackHandler implements AMRMClientAsync.CallbackHandler {
        @Override
        public void onContainersCompleted(List<ContainerStatus> containerStatuses) {
            //noop
        }

        @Override
        public void onContainersAllocated(List<Container> containers) {
            //noop
        }

        @Override
        public void onShutdownRequest() {
            failLauncher(0, "ResourceManager requested AM Shutdown", null);
            // TODO: OYA: interrupt?
        }

        @Override
        public void onNodesUpdated(List<NodeReport> nodeReports) {
            //noop
        }

        @Override
        public float getProgress() {
            return 0.5f;    //TODO: OYA: maybe some action types can report better progress?
        }

        @Override
        public void onError(final Throwable ex) {
            failLauncher(0, ex.getMessage(), ex);
            // TODO: OYA: interrupt?
        }
    }

    public static String[] getMainArguments(Configuration conf) {
        String[] args = new String[conf.getInt(CONF_OOZIE_ACTION_MAIN_ARG_COUNT, 0)];
        for (int i = 0; i < args.length; i++) {
            args[i] = conf.get(CONF_OOZIE_ACTION_MAIN_ARG_PREFIX + i);
        }
        return args;
    }

    private static class LauncherSecurityManager extends SecurityManager {
        private boolean exitInvoked;
        private int exitCode;
        private SecurityManager securityManager;

        public LauncherSecurityManager() {
            exitInvoked = false;
            exitCode = 0;
            securityManager = System.getSecurityManager();
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            if (securityManager != null) {
                // check everything with the original SecurityManager
                securityManager.checkPermission(perm, context);
            }
        }

        @Override
        public void checkPermission(Permission perm) {
            if (securityManager != null) {
                // check everything with the original SecurityManager
                securityManager.checkPermission(perm);
            }
        }

        @Override
        public void checkExit(int status) throws SecurityException {
            exitInvoked = true;
            exitCode = status;
            throw new SecurityException("Intercepted System.exit(" + status + ")");
        }

        public boolean getExitInvoked() {
            return exitInvoked;
        }

        public int getExitCode() {
            return exitCode;
        }

        public void set() {
            if (System.getSecurityManager() != this) {
                System.setSecurityManager(this);
            }
        }

        public void unset() {
            if (System.getSecurityManager() == this) {
                System.setSecurityManager(securityManager);
            }
        }
    }


    /**
     * Print files and directories in current directory. Will list files in the sub-directory (only 1 level deep)
     */
    protected static void printContentsOfCurrentDir() {
        File folder = new File(".");
        System.out.println();
        System.out.println("Files in current dir:" + folder.getAbsolutePath());
        System.out.println("======================");

        File[] listOfFiles = folder.listFiles();
        for (File fileName : listOfFiles) {
            if (fileName.isFile()) {
                System.out.println("File: " + fileName.getName());
            } else if (fileName.isDirectory()) {
                System.out.println("Dir: " + fileName.getName());
                File subDir = new File(fileName.getName());
                File[] moreFiles = subDir.listFiles();
                for (File subFileName : moreFiles) {
                    if (subFileName.isFile()) {
                        System.out.println("  File: " + subFileName.getName());
                    } else if (subFileName.isDirectory()) {
                        System.out.println("  Dir: " + subFileName.getName());
                    }
                }
            }
        }
    }

    protected static Configuration readLauncherConf() {
        File confFile = new File(LAUNCHER_JOB_CONF_XML);
        Configuration conf = new Configuration(false);
        conf.addResource(new Path(confFile.getAbsolutePath()));
        return conf;
    }

    protected static class ErrorHolder {
        private int errorCode = 0;
        private Throwable errorCause = null;
        private String errorMessage = null;

        public int getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(int errorCode) {
            this.errorCode = errorCode;
        }

        public Throwable getErrorCause() {
            return errorCause;
        }

        public void setErrorCause(Throwable errorCause) {
            this.errorCause = errorCause;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
