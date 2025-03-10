/**
 * (C) Copyright IBM Corporation 2021, 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.openliberty.tools.maven.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument;
import io.openliberty.tools.common.plugins.config.XmlDocument;
import io.openliberty.tools.common.plugins.util.BinaryScannerUtil;
import static io.openliberty.tools.common.plugins.util.BinaryScannerUtil.*;
import io.openliberty.tools.common.plugins.util.PluginExecutionException;
import io.openliberty.tools.common.plugins.util.ServerFeatureUtil;
import io.openliberty.tools.maven.ServerFeatureSupport;

/**
 * This mojo generates the features required in the featureManager element in
 * server.xml. It examines the dependencies declared in the pom.xml and the
 * features already declared in the featureManager elements in the XML
 * configuration files. Then it generates any missing feature names and stores
 * them in a new featureManager element in a new XML file in the source
 * config/dropins directory.
 */
@Mojo(name = "generate-features")
public class GenerateFeaturesMojo extends ServerFeatureSupport {

    public static final String FEATURES_FILE_MESSAGE = "The Liberty Maven Plugin has generated Liberty features necessary for your application in "
            + GENERATED_FEATURES_FILE_PATH;
    public static final String HEADER = "This file was generated by the Liberty Maven Plugin and will be overwritten on subsequent runs of the liberty:generate-features goal."
            + "\n It is recommended that you do not edit this file and that you commit this file to your version control.";
    public static final String GENERATED_FEATURES_COMMENT = "The following features were generated based on API usage detected in your application";
    public static final String NO_NEW_FEATURES_COMMENT = "No additional features generated";
    public static final String NO_CLASSES_DIR_WARNING = "Could not find classes directory to generate features against. Liberty features will not be generated. "
            + "Ensure your project has first been compiled.";

    private File binaryScanner;

    @Parameter(property = "classFiles")
    private List<String> classFiles;

    /**
     * If optimize is true, pass all class files and only user specified features to binary scanner
     * Otherwise, if optimize is false, pass only provided updated class files (if any) and all existing features to binary scanner
     */
    @Parameter(property = "optimize", defaultValue = "true")
    private boolean optimize;

    /*
     * (non-Javadoc)
     * @see org.codehaus.mojo.pluginsupport.MojoSupport#doExecute()
     */
    @Override
    protected void doExecute() throws Exception {
        if (skip) {
            log.info("\nSkipping generate-features goal.\n");
            return;
        }
        generateFeatures();
    }

    @Override
    protected void init() throws MojoExecutionException, MojoFailureException {
        // @see io.openliberty.tools.maven.BasicSupport#init() skip server config
        // setup as generate features does not require the server to be set up install
        // dir, wlp dir, outputdir, etc.
        this.skipServerConfigSetup = true;
        super.init();
    }

    /**
     * Generates features for the application given the API usage detected and
     * taking any user specified features into account
     * 
     * @throws MojoExecutionException
     * @throws PluginExecutionException indicates the binary-app-scanner.jar could
     *                                  not be found
     */
    private void generateFeatures() throws MojoExecutionException, PluginExecutionException {
        // If there are downstream projects (e.g. other modules depend on this module in the Maven Reactor build order),
        // then skip generate-features on this module
        ProjectDependencyGraph graph = session.getProjectDependencyGraph();
        List<MavenProject> upstreamProjects = new ArrayList<MavenProject>();
        if (graph != null) {
            checkMultiModuleConflicts(graph);
            List<MavenProject> downstreamProjects = graph.getDownstreamProjects(project, true);
            if (!downstreamProjects.isEmpty()) {
                log.debug("Downstream projects: " + downstreamProjects);
                return;
            } else {
                // get all upstream projects
                for (MavenProject upstreamProj : graph.getUpstreamProjects(project, true)) {
                    try {
                        // when GenerateFeaturesMojo is called from dev mode on a multi module project,
                        // the upstream project umbrella dependencies may not be up to date. Call
                        // getMavenProject to rebuild the project with the current Maven session,
                        // ensuring that the latest umbrella dependencies are loaded
                        upstreamProjects.add(getMavenProject(upstreamProj.getFile()));
                    } catch (ProjectBuildingException e) {
                        log.debug("Could not resolve the upstream project: " + upstreamProj.getFile()
                                + " using the current Maven session. Falling back to last resolved upstream project.");
                        upstreamProjects.add(upstreamProj); // fail gracefully, use last resolved project
                    }
                }
            }

            if (containsPreviousLibertyModule(graph)) {
                // skip this module
                return;
            }
        }

        binaryScanner = getBinaryScannerJarFromRepository();
        BinaryScannerHandler binaryScannerHandler = new BinaryScannerHandler(binaryScanner);

        log.debug("--- Generate Features values ---");
        log.debug("Binary scanner jar: " + binaryScanner.getName());
        log.debug("optimize generate features: " + optimize);
        if (classFiles != null && !classFiles.isEmpty()) {
            log.debug("Generate features for the following class files: " + classFiles.toString());
        }

        // TODO add support for env variables
        // commented out for now as the current logic depends on the server dir existing
        // and specifying features with env variables is an edge case
        /* Map<String, File> libertyDirPropertyFiles;
        try {
            libertyDirPropertyFiles = BasicSupport.getLibertyDirectoryPropertyFiles(installDirectory, userDirectory, serverDirectory);
        } catch (IOException e) {
            log.debug("Exception reading the server property files", e);
            log.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        } */

        // TODO: get user specified features that have not yet been installed in the
        // original case they appear in a server config xml document.
        // getSpecifiedFeatures may not return the features in the correct case
        // Set<String> featuresToInstall = getSpecifiedFeatures(null); 

        // get existing server features from source directory
        ServerFeatureUtil servUtil = getServerFeatureUtil(true);

        Set<String> generatedFiles = new HashSet<String>();
        generatedFiles.add(GENERATED_FEATURES_FILE_NAME);

        Set<String> existingFeatures = getServerFeatures(servUtil, generatedFiles, optimize);
        Set<String> nonCustomFeatures = new HashSet<String>(); // binary scanner only handles actual Liberty features
        for (String feature : existingFeatures) { // custom features are "usr:feature-1.0" or "myExt:feature-2.0"
            if (!feature.contains(":")) nonCustomFeatures.add(feature);
        }

        Set<String> scannedFeatureList = null;
        String eeVersion = null;
        String mpVersion = null;
        try {
            List<MavenProject> mavenProjects = new ArrayList<MavenProject>();
            mavenProjects.addAll(upstreamProjects);
            mavenProjects.add(project);
            Set<String> directories = getClassesDirectories(mavenProjects);
            if (directories.isEmpty() && (classFiles == null || classFiles.isEmpty())) {
                // log as warning and continue to call binary scanner to detect conflicts in
                // user specified features
                log.warn(NO_CLASSES_DIR_WARNING);
            }
            eeVersion = getEEVersion(mavenProjects);
            mpVersion = getMPVersion(mavenProjects);

            String logLocation = project.getBuild().getDirectory();
            String eeVersionArg = composeEEVersion(eeVersion);
            String mpVersionArg = composeMPVersion(mpVersion);
            scannedFeatureList = binaryScannerHandler.runBinaryScanner(nonCustomFeatures, classFiles, directories, logLocation, eeVersionArg, mpVersionArg, optimize);
        } catch (BinaryScannerUtil.NoRecommendationException noRecommendation) {
            throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE3, noRecommendation.getConflicts()));
        } catch (BinaryScannerUtil.FeatureModifiedException featuresModified) {
            Set<String> userFeatures = (optimize) ? existingFeatures :
                getServerFeatures(servUtil, generatedFiles, true); // user features excludes generatedFiles
            Set<String> modifiedSet = featuresModified.getFeatures(); // a set that works after being modified by the scanner
            if (modifiedSet.containsAll(userFeatures)) {
                // none of the user features were modified, only features which were generated earlier.
                log.debug(
                        "FeatureModifiedException, modifiedSet containsAll userFeatures, pass modifiedSet on to generateFeatures");
                // features were modified to get a working set with the application's API usage, display warning to users and use modified set
                log.warn(featuresModified.getMessage());
                scannedFeatureList = modifiedSet;
            } else {
                Set<String> allAppFeatures = featuresModified.getSuggestions(); // suggestions are scanned from binaries
                allAppFeatures.addAll(userFeatures); // scanned plus configured features were detected to be in conflict
                log.debug("FeatureModifiedException, combine suggestions from scanner with user features in error msg");
                throw new MojoExecutionException(
                        String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE1, allAppFeatures, modifiedSet));

            }
        } catch (BinaryScannerUtil.RecommendationSetException showRecommendation) {
            if (showRecommendation.isExistingFeaturesConflict()) {
                throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE2, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            } else {
                throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE1, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            }
        } catch (BinaryScannerUtil.FeatureUnavailableException featureUnavailable) {
            throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE5, featureUnavailable.getConflicts(), featureUnavailable.getMPLevel(), featureUnavailable.getEELevel(), featureUnavailable.getUnavailableFeatures()));
        } catch (BinaryScannerUtil.IllegalTargetComboException illegalCombo) {
            throw new MojoExecutionException(String.format(BinaryScannerUtil.BINARY_SCANNER_INVALID_COMBO_MESSAGE, eeVersion, mpVersion));
        } catch (BinaryScannerUtil.IllegalTargetException illegalTargets) {
            String messages = buildInvalidArgExceptionMessage(illegalTargets.getEELevel(), illegalTargets.getMPLevel(), eeVersion, mpVersion);
            throw new MojoExecutionException(messages);
        } catch (PluginExecutionException x) {
            // throw an error when there is a problem not caught in runBinaryScanner()
            Object o = x.getCause();
            if (o != null) {
                log.debug("Caused by exception:" + x.getCause().getClass().getName());
                log.debug("Caused by exception message:" + x.getCause().getMessage());
            }
            throw new MojoExecutionException("Failed to generate a working set of features. " + x.getMessage(), x);
        }

        Set<String> missingLibertyFeatures = new HashSet<String>();
        if (scannedFeatureList != null) {
            missingLibertyFeatures.addAll(scannedFeatureList);

            servUtil.setLowerCaseFeatures(false);
            // get set of user defined features so they can be omitted from the generated
            // file that will be written
            Set<String> userDefinedFeatures = optimize ? existingFeatures
                    : servUtil.getServerFeatures(configDirectory, serverXmlFile, new HashMap<String, File>(),
                            generatedFiles);
            log.debug("User defined features:" + userDefinedFeatures);
            servUtil.setLowerCaseFeatures(true);
            if (userDefinedFeatures != null) {
                missingLibertyFeatures.removeAll(userDefinedFeatures);
            }
        }
        log.debug("Features detected by binary scanner which are not in server.xml" + missingLibertyFeatures);

        File newServerXmlSrc = new File(configDirectory, GENERATED_FEATURES_FILE_PATH);
        File serverXml = findConfigFile("server.xml", serverXmlFile);
        ServerConfigXmlDocument doc = getServerXmlDocFromConfig(serverXml);
        log.debug("Xml document we'll try to update after generate features doc=" + doc + " file=" + serverXml);

        try {
            if (missingLibertyFeatures.size() > 0) {
                Set<String> existingGeneratedFeatures = getGeneratedFeatures(servUtil, newServerXmlSrc);
                if (!missingLibertyFeatures.equals(existingGeneratedFeatures)) {
                    // Create special XML file to contain generated features.
                    ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                    configDocument.createComment(HEADER);
                    Element featureManagerElem = configDocument.createFeatureManager();
                    configDocument.createComment(featureManagerElem, GENERATED_FEATURES_COMMENT);
                    for (String missing : missingLibertyFeatures) {
                        log.debug(String.format("Adding missing feature %s to %s.", missing, GENERATED_FEATURES_FILE_PATH));
                        configDocument.createFeature(missing);
                    }
                    // Generate log message before writing file as the file change event kicks off other dev mode actions
                    log.info("Generated the following features: " + missingLibertyFeatures);
                    configDocument.writeXMLDocument(newServerXmlSrc);
                    log.debug("Created file " + newServerXmlSrc);
                    // Add a reference to this new file in existing server.xml.
                    addGenerationCommentToConfig(doc, serverXml);
                } else {
                    log.info("Regenerated the following features: " + missingLibertyFeatures);
                }
            } else {
                log.info("No additional features were generated.");
                if (newServerXmlSrc.exists()) {
                    // generated-features.xml exists but no additional features were generated
                    // create empty features list with comment
                    ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                    configDocument.createComment(HEADER);
                    Element featureManagerElem = configDocument.createFeatureManager();
                    configDocument.createComment(featureManagerElem, NO_NEW_FEATURES_COMMENT);
                    configDocument.writeXMLDocument(newServerXmlSrc);
                }
            }
        } catch (ParserConfigurationException | TransformerException | IOException e) {
            log.debug("Exception creating the server features file", e);
                throw new MojoExecutionException(
                        "Automatic generation of features failed. Error attempting to create the "
                                + GENERATED_FEATURES_FILE_NAME
                                + ". Ensure your id has write permission to the server configuration directory.",
                        e);
        }
    }

    // Get the features from the server config and optionally exclude the specified config files from the search.
    private Set<String> getServerFeatures(ServerFeatureUtil servUtil, Set<String> generatedFiles, boolean excludeGenerated) {
        servUtil.setLowerCaseFeatures(false);
        // if optimizing, ignore generated files when passing in existing features to
        // binary scanner
        Set<String> existingFeatures = servUtil.getServerFeatures(configDirectory, serverXmlFile,
                new HashMap<String, File>(), excludeGenerated ? generatedFiles : null); // pass generatedFiles to exclude them
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        servUtil.setLowerCaseFeatures(true);
        return existingFeatures;
    }

    // returns the features specified in the generated-features.xml file
    private Set<String> getGeneratedFeatures(ServerFeatureUtil servUtil, File generatedFeaturesFile) {
        servUtil.setLowerCaseFeatures(false);
        Set<String> genFeatSet = new HashSet<String>();
        servUtil.getServerXmlFeatures(genFeatSet, configDirectory,
                generatedFeaturesFile, null, null);
        servUtil.setLowerCaseFeatures(true);
        return genFeatSet;
    }

    /**
     * Gets the binary scanner jar file from the local cache.
     * Downloads it first from connected repositories such as Maven Central if a newer release is available than the cached version.
     * Note: Maven updates artifacts daily by default based on the last updated timestamp. Users should use 'mvn -U' to force updates if needed.
     * 
     * @return The File object of the binary scanner jar in the local cache.
     * @throws PluginExecutionException
     */
    private File getBinaryScannerJarFromRepository() throws PluginExecutionException {
        try {
            return getArtifact(BINARY_SCANNER_MAVEN_GROUP_ID, BINARY_SCANNER_MAVEN_ARTIFACT_ID, BINARY_SCANNER_MAVEN_TYPE, BINARY_SCANNER_MAVEN_VERSION).getFile();
        } catch (Exception e) {
            throw new PluginExecutionException("Could not retrieve the artifact " + BINARY_SCANNER_MAVEN_GROUP_ID + "."
                    + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + " needed for liberty:generate-features. Ensure you have a connection to Maven Central or another repository that contains the "
                    + BINARY_SCANNER_MAVEN_GROUP_ID + "." + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + ".jar configured in your pom.xml.",
                    e);
        }
    }

    /*
     * Return specificFile if it exists; otherwise return the file with the requested fileName from the 
     * configDirectory, but only if it exists. Null is returned if the file does not exist in either location.
     */
    private File findConfigFile(String fileName, File specificFile) {
        if (specificFile != null && specificFile.exists()) {
            return specificFile;
        }

        File f = new File(configDirectory, fileName);
        if (configDirectory != null && f.exists()) {
            return f;
        }
        return null;
    }

    private ServerConfigXmlDocument getServerXmlDocFromConfig(File serverXml) {
        if (serverXml == null || !serverXml.exists()) {
            return null;
        }
        try {
            return ServerConfigXmlDocument.newInstance(serverXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.debug("Exception creating server.xml object model", e);
        }
        return null;
    }

    /**
     * Remove the comment in server.xml that warns we created another file with features in it.
     */
    private void removeGenerationCommentFromConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            doc.removeFMComment(FEATURES_FILE_MESSAGE);
            doc.writeXMLDocument(serverXml);
        } catch (IOException | TransformerException e) {
            log.debug("Exception removing comment from server.xml", e);
        }
        return;
    }

    /**
     * Add a comment to server.xml to warn them we created another file with features in it.
     * Only writes the file if the comment does not exist yet.
     */
    private void addGenerationCommentToConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            if (doc.createFMComment(FEATURES_FILE_MESSAGE)) {
                doc.writeXMLDocument(serverXml);
                XmlDocument.addNewlineBeforeFirstElement(serverXml);
            }
        } catch (IOException | TransformerException e) {
            log.debug("Exception adding comment to server.xml", e);
        }
        return;
    }

    // Return a list containing the classes directory of the Maven projects (upstream projects and main project)
    private Set<String> getClassesDirectories(List<MavenProject> mavenProjects) throws MojoExecutionException {
        Set<String> dirs = new HashSet<String>();
        String classesDirName = null;
        log.debug("For binary scanner gathering Java build output directories for Maven projects, size="
                + mavenProjects.size());
        for (MavenProject mavenProject : mavenProjects) {
            classesDirName = getClassesDirectory(mavenProject.getBuild().getOutputDirectory());
            if (classesDirName != null) {
                dirs.add(classesDirName);
            }
        }
        for (String s : dirs) {
            log.debug("Found dir:" + s);
        }
        return dirs;
    }

    // Check one directory and if it exists return its canonical path (or absolute path if error).
    private String getClassesDirectory(String outputDir) {
        File classesDir = new File(outputDir);
        try {
            if (classesDir.exists()) {
                return classesDir.getCanonicalPath();
            }
        } catch (IOException x) {
            String classesDirAbsPath = classesDir.getAbsolutePath();
            log.debug("IOException obtaining canonical path name for a project's classes directory: " + classesDirAbsPath);
            return classesDirAbsPath;
        }
        return null; // directory does not exist.
    }

    /**
     * Returns the EE major version detected for the given MavenProjects
     * 
     * @param mavenProjects project modules, for single module projects list of size 1
     * @return the latest version of EE detected across multiple project modules,
     *         null if an EE version is not found or the version number is out of range
     */
    public String getEEVersion(List<MavenProject> mavenProjects) {
        String eeVersion = null;
        if (mavenProjects != null) {
            Set<String> eeVersionsDetected = new HashSet<String>();
            for (MavenProject mavenProject : mavenProjects) {
                try {
                    String ver = getEEVersion(mavenProject);
                    log.debug("Java and/or Jakarta EE umbrella dependency found in project: " + mavenProject.getName());
                    if (ver != null) {
                        eeVersionsDetected.add(ver);
                    }
                } catch (NoUmbrellaDependencyException e) {
                    // umbrella dependency does not exist, do nothing
                }
            }
            if (!eeVersionsDetected.isEmpty()) {
                eeVersion = eeVersionsDetected.iterator().next();
                // if multiple EE versions are found across multiple modules, return the latest version
                for (String ver : eeVersionsDetected) {
                    if (ver.compareTo(eeVersion) > 0) {
                        eeVersion = ver;
                    }
                }
            }
            if (eeVersionsDetected.size() > 1) {
                log.debug(
                        "Multiple Java and/or Jakarta EE versions found across multiple project modules, using the latest version ("
                                + eeVersion + ") found to generate Liberty features.");
            }
        }
        return eeVersion;
    }

    /**
     * Returns the EE major version detected for the given MavenProject.
     * To match the Maven "nearest in the dependency tree" strategy, this method
     * will return the first EE umbrella dependency version detected.
     * 
     * @param project the MavenProject to search
     * @return EE major version corresponding to the EE umbrella dependency
     * @throws NoUmbrellaDependencyException indicates that the umbrella dependency was not found
     */
    private String getEEVersion(MavenProject project) throws NoUmbrellaDependencyException {
        if (project != null) {
            List<Dependency> dependencies = project.getDependencies();
            for (Dependency d : dependencies) {
                if (!d.getScope().equals("provided")) {
                    continue;
                }
                if ((d.getGroupId().equals("javax") && d.getArtifactId().equals("javaee-api")) ||
                    (d.getGroupId().equals("jakarta.platform") && d.getArtifactId().equals("jakarta.jakartaee-api"))) {
                    return d.getVersion();
                }
            }
        }
        throw new NoUmbrellaDependencyException();
    }

    /**
     * Returns the MicroProfile version detected for the given MavenProjects
     * 
     * @param mavenProjects project modules, for single module projects list of size 1
     * @return the latest version of MP detected across multiple project modules,
     *         null if an MP version is not found or the version number is out of range
     */
    public String getMPVersion(List<MavenProject> mavenProjects) {
        String mpVersion = null;
        if (mavenProjects != null) {
            Set<String> mpVersionsDetected = new HashSet<String>();
            for (MavenProject mavenProject : mavenProjects) {
                try {
                    String ver = getMPVersion(mavenProject);
                    log.debug("MicroProfile umbrella dependency found in project: " + mavenProject.getName());
                    if (ver != null) {
                        mpVersionsDetected.add(ver);
                    }
                } catch (NoUmbrellaDependencyException e) {
                    // umbrella dependency does not exist, do nothing
                }
            }
            if (!mpVersionsDetected.isEmpty()) {
                mpVersion = mpVersionsDetected.iterator().next();
                // if multiple MP versions are found across multiple modules, return the latest version
                for (String ver : mpVersionsDetected) {
                    if (ver.compareTo(mpVersion) > 0) {
                        mpVersion = ver;
                    }
                }
            }
            if (mpVersionsDetected.size() > 1) {
                log.debug(
                        "Multiple MicroProfile versions found across multiple project modules, using the latest version ("
                                + mpVersion + ") found to generate Liberty features.");
            }
        }
        return mpVersion;
    }

    /**
     * Returns the MicroProfile (MP) version detected for the given MavenProject
     * To match the Maven "nearest in the dependency tree" strategy, this method
     * will return the first MP umbrella dependency version detected.
     * 
     * @param project the MavenProject to search
     * @return MP exact version code corresponding to the MP umbrella dependency
     * @throws NoUmbrellaDependencyException indicates that the umbrella dependency was not found
     */
    public String getMPVersion(MavenProject project) throws NoUmbrellaDependencyException { // figure out correct level of MP from declared dependencies
        if (project != null) {
            List<Dependency> dependencies = project.getDependencies();
            for (Dependency d : dependencies) {
                if (!d.getScope().equals("provided")) {
                    continue;
                }
                if (d.getGroupId().equals("org.eclipse.microprofile") &&
                        d.getArtifactId().equals("microprofile")) {
                    return d.getVersion();
                }
            }
        }
        throw new NoUmbrellaDependencyException();
    }

    // Define the logging functions of the binary scanner handler and make it available in this plugin
    private class BinaryScannerHandler extends BinaryScannerUtil {
        BinaryScannerHandler(File scannerFile) {
            super(scannerFile);
        }
        @Override
        public void debug(String msg) {
            log.debug(msg);
        }
        @Override
        public void debug(String msg, Throwable t) {
            log.debug(msg, t);
        }
        @Override
        public void error(String msg) {
            log.error(msg);
        }
        @Override
        public void warn(String msg) {
            log.warn(msg);
        }
        @Override
        public void info(String msg) {
            log.info(msg);
        }
        @Override
        public boolean isDebugEnabled() {
            return log.isDebugEnabled();
        }
    }

    // using the current MavenSession build the project (resolves dependencies)
    private MavenProject getMavenProject(File buildFile) throws ProjectBuildingException {
        ProjectBuildingResult build = mavenProjectBuilder.build(buildFile,
                session.getProjectBuildingRequest().setResolveDependencies(true));
        return build.getProject();
    }

    /**
     * Class to indicate that an umbrella dependency was not found in the build file
     */
    public class NoUmbrellaDependencyException extends Exception {
        private static final long serialVersionUID = 1L;
    }

}
