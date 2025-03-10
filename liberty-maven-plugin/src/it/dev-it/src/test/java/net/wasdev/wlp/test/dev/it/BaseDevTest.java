/*******************************************************************************
 * (c) Copyright IBM Corporation 2019, 2023.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.wlp.test.dev.it;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.input.ReversedLinesFileReader;
import org.apache.maven.shared.utils.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class BaseDevTest {

   static String customLibertyModule;
   static String customPomModule;
   static File tempProj;
   static File basicDevProj;
   static File logFile;
   static File logErrorFile;
   static File targetDir;
   static File pom;
   static BufferedWriter writer;
   static Process process;
   final static String GENERATED_FEATURES_FILE_NAME = "generated-features.xml";
   final static String RUNNING_GENERATE_FEATURES = "Running liberty:generate-features";
   final static String REGENERATE_FEATURES = "Regenerated the following features:";
   final static String GENERATE_FEATURES = "Generated the following features:";
   final static String SERVER_XML_COMMENT = "Plugin has generated Liberty features"; // the explanation added to server.xml
   final static String NEW_FILE_INFO_MESSAGE = "This file was generated by the Liberty Maven Plugin and will be overwritten"; // the explanation added to the generated features file
   final static String SERVER_CONFIG_SUCCESS = "CWWKZ0003I:";// CWWKZ0003I: The application xxx updated in y.yyy seconds.
   final static String SERVER_UPDATE_COMPLETE = "CWWKF0008I:"; // Feature update completed in 0.649 seconds.
   final static String SERVER_INSTALLED_FEATURES = "CWWKF0012I: The server installed the following features:";
   final static String WEB_APP_AVAILABLE = "CWWKT0016I:"; // Web application available...
   final static String SERVER_UPDATED = "CWWKG0017I: The server configuration was successfully updated";
   final static String SERVER_NOT_UPDATED = "CWWKG0018I: The server configuration was not updated.";
   final static String COMPILATION_SUCCESSFUL = "Source compilation was successful.";
   final static String COMPILATION_ERRORS = "Source compilation had errors.";

   protected static void setUpBeforeClass(String devModeParams) throws IOException, InterruptedException, FileNotFoundException {
   	setUpBeforeClass(devModeParams, "../resources/basic-dev-project");
   }

   protected static void setUpBeforeClass(String devModeParams, boolean isDevMode) throws IOException, InterruptedException, FileNotFoundException {
      setUpBeforeClass(devModeParams, "../resources/basic-dev-project", isDevMode);
   }

   protected static void setUpBeforeClass(String devModeParams, String projectRoot) throws IOException, InterruptedException, FileNotFoundException {
      setUpBeforeClass(devModeParams, projectRoot, true);
   }

   protected static void setUpBeforeClass(String params, String projectRoot, boolean isDevMode) throws IOException, InterruptedException, FileNotFoundException {
      setUpBeforeClass(params, projectRoot, isDevMode, true, null, null);
   }

   /**
    * Setup and optionally start dev/run
    *
    * @param params Params for the dev/run goal
    * @param projectRoot The Maven project root
    * @param isDevMode Use dev if true, use run if false.  Ignored if startProcessDuringSetup is false.
    * @param startProcessDuringSetup If this method should start the actual dev/run process
    * @param libertyConfigModule For multi module project, the module where Liberty configuration is located
    * @param pomModule For multi module project, the module where the pom is located.  If null, use the project root.
    * @throws IOException
    * @throws InterruptedException
    * @throws FileNotFoundException
    */
   protected static void setUpBeforeClass(String params, String projectRoot, boolean isDevMode, boolean startProcessDuringSetup, String libertyConfigModule, String pomModule) throws IOException, InterruptedException, FileNotFoundException {
      customLibertyModule = libertyConfigModule;
      customPomModule = pomModule;

      basicDevProj = new File(projectRoot);

      tempProj = Files.createTempDirectory("temp").toFile();
      assertTrue("temp directory does not exist", tempProj.exists());

      assertTrue(projectRoot+" directory does not exist", basicDevProj.exists());

      FileUtils.copyDirectoryStructure(basicDevProj, tempProj);
      assertTrue("temp directory does not contain expected copied files from "+projectRoot, tempProj.listFiles().length > 0);

      // in case cleanup was not successful, try to delete the various log files so we can proceed
      logFile = new File(basicDevProj, "logFile.txt");
      if (logFile.exists()) {
         assertTrue("Could not delete log file: "+logFile.getCanonicalPath(), logFile.delete());
      }
      assertTrue("log file already existed: "+logFile.getCanonicalPath(), logFile.createNewFile());
      logErrorFile = new File(basicDevProj, "logErrorFile.txt");
      if (logErrorFile.exists()) {
          assertTrue("Could not delete logError file: "+logErrorFile.getCanonicalPath(), logErrorFile.delete());
       }
      assertTrue("logError file already existed: "+logErrorFile.getCanonicalPath(), logErrorFile.createNewFile());

      if (customPomModule == null) {
         pom = new File(tempProj, "pom.xml");
      } else {
         pom = new File(new File(tempProj, customPomModule), "pom.xml");
      }
      assertTrue(pom.getCanonicalPath()+" file does not exist", pom.exists());

      replaceVersion();

      if (startProcessDuringSetup) {
         startProcess(params, isDevMode);
      }
   }

   protected static void startProcess(String params, boolean isDevMode) throws IOException, InterruptedException, FileNotFoundException {
      startProcess(params, isDevMode, "mvn liberty:");
   }

   protected static void startProcess(String params, boolean isDevMode, String mavenPluginCommand) throws IOException, InterruptedException, FileNotFoundException {
      startProcess(params, isDevMode, mavenPluginCommand, true);
   }

   protected static void startProcess(String params, boolean isDevMode, String mavenPluginCommand, boolean verifyServerStart) throws IOException, InterruptedException, FileNotFoundException {
      // run dev mode on project
      String goal;
      if(isDevMode) {
         goal = "dev -DgenerateFeatures=true";
      } else {
         goal = "run";
      }

      StringBuilder command = new StringBuilder(mavenPluginCommand + goal);
      if (params != null) {
         command.append(" " + params);
      }
      ProcessBuilder builder = buildProcess(command.toString());

      builder.redirectOutput(logFile);
      builder.redirectError(logErrorFile);
      if (customPomModule != null) {
         builder.directory(new File(tempProj, customPomModule));
      }
      process = builder.start();
      assertTrue("process is not alive", process.isAlive());

      OutputStream stdin = process.getOutputStream();

      writer = new BufferedWriter(new OutputStreamWriter(stdin));

      if (verifyServerStart) {
         // check that the server has started
         assertTrue(getLogTail(), verifyLogMessageExists("CWWKF0011I", 120000));
         if (isDevMode) {
            assertTrue(verifyLogMessageExists("Liberty is running in dev mode.", 60000));
            // TODO: Enable this code once issue 1554 is fixed
            // // Can't start testing until compilation is complete if needed.
            // verifyLogMessageExists("Source compilation was successful.", 5000);
            // Thread.sleep(2000); // wait for dev mode to register all directories
         }

         // verify that the target directory was created
         if (customLibertyModule == null) {
            targetDir = new File(tempProj, "target");
         } else {
            targetDir = new File(new File(tempProj, customLibertyModule), "target");
         }
         assertTrue("target directory does not exist: "+targetDir.getCanonicalPath(), targetDir.exists());
      }
   }

   protected static String getLogTail() throws IOException {
      return getLogTail(logFile);
   }

   protected static String getLogTail(File log) throws IOException {
      int numLines = 100;
      ReversedLinesFileReader object = null;
      try {
         object = new ReversedLinesFileReader(log, StandardCharsets.UTF_8);
         List<String> reversedLines = new ArrayList<String>();

         for (int i = 0; i < numLines; i++) {
            String line = object.readLine();
            if (line == null) {
               break;
            }
            reversedLines.add(line);
         }
         StringBuilder result = new StringBuilder();
         for (int i = reversedLines.size() - 1; i >=0; i--) {
            result.append(reversedLines.get(i) + "\n");
         }
         return "Last "+numLines+" lines of log at "+log.getAbsolutePath()+":\n" + 
            "===================== START =======================\n" + 
            result.toString() +
            "====================== END ========================\n";
      } finally {
         if (object != null) {
            object.close();
         }
      }
   }

   protected static void cleanUpAfterClass() throws Exception {
      cleanUpAfterClass(true);
   }

   protected static void cleanUpAfterClass(boolean isDevMode) throws Exception {
      cleanUpAfterClass(isDevMode, true);
   }

   protected static void cleanUpAfterClass(boolean isDevMode, boolean checkForShutdownMessage) throws Exception {
      stopProcess(isDevMode, checkForShutdownMessage);

      if (tempProj != null && tempProj.exists()) {
         FileUtils.deleteDirectory(tempProj);
      }

      if (logFile != null && logFile.exists()) {
          assertTrue("Could not delete log file: "+logFile.getCanonicalPath(), logFile.delete());
       }
      if (logErrorFile != null && logErrorFile.exists()) {
          assertTrue("Could not delete logError file: "+logErrorFile.getCanonicalPath(), logErrorFile.delete());
       }
   }

   protected static void clearLogFile() throws Exception {
      if (logFile != null && logFile.exists()) {
         BufferedWriter logWriter = new BufferedWriter(new FileWriter(logFile));
         logWriter.close();
      }
   }

   private static void stopProcess(boolean isDevMode, boolean checkForShutdownMessage) throws IOException, InterruptedException, FileNotFoundException, IllegalThreadStateException {
      // shut down dev mode
      if (writer != null) {
         int serverStoppedOccurrences = countOccurrences("CWWKE0036I", logFile);

         try {
            if(isDevMode) {
               writer.write("exit\n"); // trigger dev mode to shut down
            } else {
               process.destroy(); // stop run
            }
            writer.flush();

         } catch (IOException e) {
         } finally {
            try {
               writer.close();
            } catch (IOException io) {
            }
         }

         try {
            process.waitFor(120, TimeUnit.SECONDS);
         } catch (InterruptedException e) {
         }

         // test that the server has shut down
         if (checkForShutdownMessage) {
            assertTrue(getLogTail(), verifyLogMessageExists("CWWKE0036I", 20000, ++serverStoppedOccurrences));
         }
      }
   }

   protected static void testModifyJavaFile() throws IOException, InterruptedException {
      // modify a java file
      File srcHelloWorld = new File(tempProj, "src/main/java/com/demo/HelloWorld.java");
      File targetHelloWorld = new File(targetDir, "classes/com/demo/HelloWorld.class");
      assertTrue(srcHelloWorld.exists());
      assertTrue(targetHelloWorld.exists());

      long lastModified = targetHelloWorld.lastModified();
      waitLongEnough();
      String str = "// testing";
      BufferedWriter javaWriter = null;
      try {
         javaWriter = new BufferedWriter(new FileWriter(srcHelloWorld, true));
         javaWriter.append(' ');
         javaWriter.append(str);
      } finally {
         if (javaWriter != null) {
            javaWriter.close();
         }
      }

      assertTrue(waitForCompilation(targetHelloWorld, lastModified, 5000));
   }

   protected static void testModifyJavaFileWithEncoding() throws IOException, InterruptedException {
      // modify a java file
      File srcHelloWorld = new File(tempProj, "src/main/java/com/demo/HelloWorld.java");
      File targetHelloWorld = new File(targetDir, "classes/com/demo/HelloWorld.class");
      assertTrue(srcHelloWorld.exists());
      assertTrue(targetHelloWorld.exists());

      waitLongEnough();
      BufferedWriter javaWriter = null;
      try {
          javaWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(srcHelloWorld, true), StandardCharsets.ISO_8859_1));
          javaWriter.append(' ');
          javaWriter.append("// libert\u00E9");
      } finally {
          if (javaWriter != null) {
              javaWriter.close();
          }
      }

      assertTrue(verifyLogMessageDoesNotExist("unmappable character (0xE9) for encoding UTF-8", 5000, logErrorFile));
   }

   protected static void testModifyWithRecompileDeps() throws IOException, InterruptedException {
      File targetHelloLogger = getTargetFile("src/main/java/com/demo/HelloLogger.java",
            "classes/com/demo/HelloLogger.class");
      long helloLoggerLastModified = targetHelloLogger.lastModified();

      File targetHelloServlet = getTargetFile("src/main/java/com/demo/HelloServlet.java",
            "classes/com/demo/HelloServlet.class");
      long helloServletLastModified = targetHelloServlet.lastModified();

      testModifyJavaFile(); // this method waits long enough

      // check that all files were recompiled
      assertTrue(waitForCompilation(targetHelloLogger, helloLoggerLastModified, 1000));
      assertTrue(waitForCompilation(targetHelloServlet, helloServletLastModified, 1000));
   }

   protected static boolean readFile(String str, File file) throws FileNotFoundException, IOException {
      BufferedReader br = new BufferedReader(new FileReader(file));
      String line = br.readLine();
      try {
         while (line != null) {
            if (line.contains(str)) {
               return true;
            }
            line = br.readLine();
         }
      } finally {
         br.close();
      }
      return false;
   }

   /**
    * Count number of lines that contain the given string
    */
   protected static int countOccurrences(String str, File file) throws FileNotFoundException, IOException {
      int occurrences = 0;
      BufferedReader br = new BufferedReader(new FileReader(file));
      String line = br.readLine();
      try {
         while (line != null) {
            if (line.contains(str)) {
               occurrences++;
            }
            line = br.readLine();
         }
      } finally {
         br.close();
      }
      return occurrences;
   }

   protected static ProcessBuilder buildProcess(String processCommand) {
      ProcessBuilder builder = new ProcessBuilder();
      builder.directory(tempProj);

      String os = System.getProperty("os.name");
      if (os != null && os.toLowerCase().startsWith("windows")) {
         builder.command("CMD", "/C", processCommand);
      } else {
         builder.command("bash", "-c", processCommand);
      }
      return builder;
   }

   private static void replaceVersion() throws IOException {
      String pluginVersion = System.getProperty("mavenPluginVersion");
      replaceString("SUB_VERSION", pluginVersion, pom);
      String runtimeVersion = System.getProperty("runtimeVersion");
      replaceString("RUNTIME_VERSION", runtimeVersion, pom);
   }

   protected static void replaceString(String str, String replacement, File file) throws IOException {
      Path path = file.toPath();
      Charset charset = StandardCharsets.UTF_8;
      String content = new String(Files.readAllBytes(path), charset);

      content = content.replaceAll(str, replacement);
      Files.write(path, content.getBytes(charset));
   }

   protected static boolean verifyLogMessageExists(String message, int timeout)
         throws InterruptedException, FileNotFoundException, IOException {
      return verifyLogMessageExists(message, timeout, logFile);
   }

   protected static boolean verifyLogMessageExists(String message, int timeout, File log)
         throws InterruptedException, FileNotFoundException, IOException {
      int waited = 0;
      int sleep = 10;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (readFile(message, log)) {
            return true;
         }
      }
      return false;
   }

   protected static boolean verifyLogMessageExists(String message, int timeout, int occurrences)
         throws InterruptedException, FileNotFoundException, IOException {
      return verifyLogMessageExists(message, timeout, logFile, occurrences);
   }

   protected static boolean verifyLogMessageExists(String message, int timeout, File log, int occurrences)
         throws InterruptedException, FileNotFoundException, IOException {
      int waited = 0;
      int sleep = 10;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (countOccurrences(message, log) == occurrences) {
            return true;
         }
      }
      return false;
   }

   protected static boolean verifyLogMessageDoesNotExist(String message, int timeout, File log)
         throws InterruptedException, FileNotFoundException, IOException {
      int waited = 0;
      int sleep = 10;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (countOccurrences(message, log) > 0) {
            return false;
        }
      }
      return true;
   }

   protected static boolean verifyFileExists(File file, int timeout)
         throws InterruptedException {
      int waited = 0;
      int sleep = 100;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (file.exists()) {
            return true;
         }
      }
      return false;
   }

   protected static boolean verifyFileDoesNotExist(File file, int timeout) throws InterruptedException {
      int waited = 0;
      int sleep = 100;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (!file.exists()) {
            return true;
         }
      }
      return false;
   }

   protected static File getTargetFile(String srcFilePath, String targetFilePath) throws IOException, InterruptedException {
      File srcClass = new File(tempProj, srcFilePath);
      File targetClass = new File(targetDir, targetFilePath);
      assertTrue(srcClass.exists());
      assertTrue(targetClass.exists());
      return targetClass;
   }

   protected static boolean waitForCompilation(File file, long lastModified, long timeout) throws InterruptedException {
      int waited = 0;
      int sleep = 100;
      while (waited <= timeout) {
         Thread.sleep(sleep);
         waited += sleep;
         if (file.lastModified() > lastModified) {
            return true;
         }
      }
      return false;
   }

   // Wait long enough that use of java.io.File.lastModified() is reliable to indicate
   // a file has been changed between two instants of time. The problem is that the 
   // method has a resolution of just 2000ms on Windows FAT and 1000ms on MacOS HFS+.
   protected static void waitLongEnough() throws InterruptedException {
      Thread.sleep(2001);
   }

   // get generated features file in source directory
   protected static File getGeneratedFeaturesFile() throws Exception {
      return getGeneratedFeaturesFile(null);
   }

   // get generated features file in target directory
   protected static File getTargetGeneratedFeaturesFile() throws Exception {
      return getTargetGeneratedFeaturesFile(null);
   }

   // get generated features file in source directory for the corresponding
   // libertyConfigModule (module name)
   protected static File getGeneratedFeaturesFile(String libertyConfigModule) throws Exception {
      String newFeatureFilePath = libertyConfigModule == null ? "" : "/" + libertyConfigModule;
      newFeatureFilePath += "/src/main/liberty/config/configDropins/overrides/" + GENERATED_FEATURES_FILE_NAME;
      File newFeatureFile = new File(tempProj, newFeatureFilePath);
      return newFeatureFile;
   }

   // get generated features file in target directory for the corresponding
   // libertyConfigModule (module name)
   protected static File getTargetGeneratedFeaturesFile(String libertyConfigModule) throws Exception {
      String newFeatureFilePath = libertyConfigModule == null ? "" : "/" + libertyConfigModule;
      newFeatureFilePath += "/liberty/wlp/usr/servers/defaultServer/configDropins/overrides/"
            + GENERATED_FEATURES_FILE_NAME;
      File newTargetFeatureFile = new File(targetDir, newFeatureFilePath);
      return newTargetFeatureFile;
   }

   protected static void tagLog(String line) throws Exception {
      writer.write(line + "\n");
      writer.flush();
   }

    @Rule
    public TestWatcher watchman = new TestWatcher() {
        @Override
        protected void failed(Throwable thr, Description description) {
            try {
                System.out.println("Failure log in " + logFile + ", tail of contents = " + getLogTail(logFile));
            } catch (IOException e) {}
        }
    };
}
