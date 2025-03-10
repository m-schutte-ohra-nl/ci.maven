/**
 * (C) Copyright IBM Corporation 2014, 2023.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.maven;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.settings.Settings;
import org.codehaus.mojo.pluginsupport.MojoSupport;
import org.codehaus.mojo.pluginsupport.ant.AntHelper;
import org.codehaus.mojo.pluginsupport.util.ArtifactItem;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

import static java.util.Objects.requireNonNull;

/**
 * Liberty Abstract Mojo Support
 * 
 */
public abstract class AbstractLibertySupport extends MojoSupport {
    /**
     * Maven Project
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project = null;
    
    @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
    protected ArtifactRepository artifactRepository = null;
    
    /**
     * The build settings.
     */
    @Parameter(defaultValue = "${settings}", required = true, readonly = true)
    protected Settings settings;
    
    @Component(role = AntHelper.class)
    protected AntHelper ant;
    
    @Component
    protected RepositorySystem repositorySystem;

    @Parameter( defaultValue = "${repositorySystemSession}", readonly = true, required = true )
    protected RepositorySystemSession repoSession;

    @Parameter( defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true )
    protected List<RemoteRepository> repositories;
    
    @Component
    protected ProjectBuilder mavenProjectBuilder;
    
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;
    
    @Parameter(property = "reactorProjects", required = true, readonly = true)
    protected List<MavenProject> reactorProjects;
    
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", required = false, readonly = true)
    protected File multiModuleProjectDirectory = null;

    protected MavenProject getProject() {
        return project;
    }
    
    protected ArtifactRepository getArtifactRepository() {
        return artifactRepository;
    }
    
    protected void init() throws MojoExecutionException, MojoFailureException {
        super.init();
        // Initialize ant helper instance
        ant.setProject(getProject());
    }
    
    protected boolean isReactorMavenProject(Artifact artifact) {
        for (MavenProject p : reactorProjects) {
            if (p.getGroupId().equals(artifact.getGroupId()) && p.getArtifactId().equals(artifact.getArtifactId())
                    && p.getVersion().equals(artifact.getBaseVersion())) {
                return true;
            }
        }
        return false;
    }
    
    protected MavenProject getReactorMavenProject(Artifact artifact) {
        for (MavenProject p : reactorProjects) {
            // Support loose configuration to all sub-module projects in the reactorProjects object. 
            // Need to be able to retrieve all transitive dependencies in these projects.
            if (p.getGroupId().equals(artifact.getGroupId()) && p.getArtifactId().equals(artifact.getArtifactId())
                    && p.getVersion().equals(artifact.getBaseVersion())) {
                p.setArtifactFilter(new ArtifactFilter() {
                    @Override
                    public boolean include(Artifact artifact) {
                        if ("compile".equals(artifact.getScope()) || "runtime".equals(artifact.getScope())) {
                            return true;
                        }
                        return false;
                    }
                });
                return p;
            }
        }
        
        return null;
    }
    
    //
    // Override methods in org.codehaus.mojo.pluginsupport.MojoSupport to resolve/create Artifact 
    // from ArtifactItem with Maven3 APIs.
    //
    
    /**
     * Resolves the Artifact from the remote repository if necessary. If no version is specified, it will
     * be retrieved from the dependency list or from the DependencyManagement section of the pom.
     *
     *
     * @param item  The item to create an artifact for; must not be null
     * @return      The artifact for the given item
     *
     * @throws MojoExecutionException   Failed to create artifact
     */
    @Override
    protected Artifact getArtifact(final ArtifactItem item) throws MojoExecutionException {
        Artifact artifact = getResolvedArtifact(item);

        if (artifact == null) {
            throw new MojoExecutionException(
                "Unable to find artifact version of " + item.getGroupId() + ":" + item.getArtifactId()
                        + " in either project dependencies or in project dependencyManagement.");
         }

         return artifact;
    }
    
    /**
     * Resolves the Artifact from the remote repository if necessary. If no version is specified, it will
     * be retrieved from the dependency list or from the DependencyManagement section of the pom.  If no
     * dependency or dependencyManagement artifact is found for the given item a 'null' will be returned.
     *
     *
     * @param item  The item to create an artifact for; must not be null
     * @return      The artifact for the given item
     *
     * @throws MojoExecutionException   Failed to create artifact
     */
    protected Artifact getResolvedArtifact(final ArtifactItem item) throws MojoExecutionException {
        assert item != null;
        Artifact artifact = null;
        
        if (item.getVersion() != null) {
            // if version is set in ArtifactItem, it will always override the one in project dependency
            artifact = createArtifact(item);
        } else {
            // Return the artifact from the project dependency if it is available and the mojo
            // should have requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME set
            artifact = resolveFromProjectDependencies(item);
            
            if (artifact != null) {
                // in case it is not resolved yet
                if (!artifact.isResolved()) {
                    item.setVersion(artifact.getVersion());
                    artifact = createArtifact(item);
                }
            } else if (resolveFromProjectDepMgmt(item) != null) {
                // if item has no version set, try to get it from the project dependencyManagement section
                // get version from dependencyManagement
                item.setVersion(resolveFromProjectDepMgmt(item).getVersion());
                artifact = createArtifact(item);
            }
        }
        
        return artifact;
    }

    /**
     * Equivalent to {@link #getArtifact(ArtifactItem)} with an ArtifactItem
     * defined by the given the coordinates. Retrieves the main artifact (i.e. with no classifier).
     *
     * <p>This is the same as calling
     * {@link #getArtifact(String, String, String, String, String)} with {@code null} for the classifier (last paramter).</p>
     *
     * @param groupId
     *            The group ID
     * @param artifactId
     *            The artifact ID
     * @param type
     *            The type (e.g. jar)
     * @param version
     *            The version, or null to retrieve it from the dependency list
     *            or from the DependencyManagement section of the pom.
     * @return Artifact The artifact for the given item
     * @throws MojoExecutionException
     *             Failed to create artifact
     * @see #getArtifact(String, String, String, String, String)
     */
    protected Artifact getArtifact(String groupId, String artifactId, String type, String version) throws MojoExecutionException {
        return getArtifact(groupId, artifactId, type, version, null);
    }

    /**
     * Equivalent to {@link #getArtifact(ArtifactItem)} with an ArtifactItem
     * defined by the given the coordinates.
     * 
     * @param groupId
     *            The group ID
     * @param artifactId
     *            The artifact ID
     * @param type
     *            The type (e.g. jar)
     * @param version
     *            The version, or null to retrieve it from the dependency list
     *            or from the DependencyManagement section of the pom.
     * @param classifier
     *            The classifier for the artifact, or {@code null} to retrieve the main artifact.
     * @return Artifact The artifact for the given item
     * @throws MojoExecutionException
     *             Failed to create artifact
     */
    protected Artifact getArtifact(String groupId, String artifactId, String type, String version, String classifier ) throws MojoExecutionException {
        ArtifactItem item = new ArtifactItem();
        item.setGroupId(groupId);
        item.setArtifactId(artifactId);
        item.setType(type);
        item.setVersion(version);
        item.setClassifier(classifier);

        return getArtifact(item);
    }

    protected ArtifactItem createArtifactItem(String groupId, String artifactId, String type, String version) {
        return getArtifactItem( groupId, artifactId, type, version, null );
    }

    protected ArtifactItem createArtifactItem(String groupId, String artifactId, String type, String version, String classifier) {
        return getArtifactItem( groupId, artifactId, type, version, classifier );
    }

    private ArtifactItem getArtifactItem( String groupId, String artifactId, String type, String version, String classifier ) {
        ArtifactItem item = new ArtifactItem();
        item.setGroupId(groupId);
        item.setArtifactId(artifactId);
        item.setType(type);
        item.setVersion(version);
        item.setClassifier(classifier);

        return item;
    }

    /**
     * Find resolved dependencies with matching groupId:artifactId:version. Also collect transitive dependencies for those
     * resolved dependencies. The groupId is required. The artifactId and version are optional.
     * If version is null, then test scoped dependencies are omitted.
     * The artifactId can also end with a '*' to indicate a wildcard match.
     *
     * @param groupId String specifying the groupId of the Maven artifact to copy.
     * @param artifactId String specifying the artifactId of the Maven artifact to copy.
     * @param version String specifying the version of the Maven artifact to copy.
     * @param type String specifying the type of the Maven artifact to copy.
     * @param classifier String specifying the classifier of the Maven artifact to copy.
     *
     * @return Set<Artifact> A collection of Artifact objects for the resolved dependencies and transitive dependencies
     * @throws MojoExecutionException
     */
    protected Set<Artifact> getResolvedDependencyWithTransitiveDependencies( String groupId, String artifactId, String version, String type,
                                                                             String classifier ) throws MojoExecutionException {
        Set<Artifact> resolvedDependencies = new HashSet<Artifact> ();

        if (version != null) {
            // if version is set, it will always override the one in project dependency
            Artifact artifact = getArtifact(groupId, artifactId, type, version, classifier);
            if (artifact != null) {
                resolvedDependencies.add(artifact);
                findTransitiveDependencies(artifact, getProject().getArtifacts(), resolvedDependencies);
            } else {
                log.warn("Unable to find artifact matching groupId "+ groupId +", artifactId "+artifactId+", version "+version+", type "+type+" and classifier "+classifier+" in configured repositories.");
            }
        } else {
            Set<Artifact> artifacts = getProject().getArtifacts();
            boolean isWildcard = artifactId != null && artifactId.endsWith("*");
            String compareArtifactId = artifactId;
            final boolean isClassifierWildcard = classifier != null && classifier.endsWith("*");
            String compareClassifier = classifier;

            if (isWildcard) {
                // if the artifactId is "*", just match on groupId
                if (artifactId.length() == 1) {
                    compareArtifactId = null;
                    isWildcard = false;
                } else {
                    compareArtifactId = artifactId.substring(0,artifactId.length() -1);
                }
            }
            if (isClassifierWildcard) {
                if (classifier.length() == 1) {
                    compareClassifier = null;
                } else {
                    compareClassifier = classifier.substring(0, classifier.length() -1);
                }
            }
        
            for (Artifact projectArtifact : artifacts) {
                if (isMatchingProjectDependency(projectArtifact, groupId, isWildcard, compareArtifactId, isClassifierWildcard, compareClassifier)) {
                    if (!projectArtifact.isResolved()) {
                        ArtifactItem item = createArtifactItem(projectArtifact.getGroupId(), projectArtifact.getArtifactId(), projectArtifact.getType(), projectArtifact.getVersion(), projectArtifact.getClassifier());
                        projectArtifact = getArtifact(item);
                    }
                    // Ignore test-scoped artifacts, by design
                    if (!"test".equals(projectArtifact.getScope())) {
                        log.debug("Found resolved dependency from project dependencies: " + projectArtifact.getGroupId() + ":"
                            + projectArtifact.getArtifactId() + ":" + projectArtifact.getVersion());
                        resolvedDependencies.add(projectArtifact);
                        findTransitiveDependencies(projectArtifact, getProject().getArtifacts(), resolvedDependencies);
                    }
                }
            }

            if (resolvedDependencies.isEmpty() && getProject().getDependencyManagement() != null) {
                // if project has dependencyManagement section
                List<Dependency> list = getProject().getDependencyManagement().getDependencies();
            
                for (Dependency dependency : list) {
                    if (isMatchingProjectDependency(dependency, groupId, isWildcard, compareArtifactId, isClassifierWildcard, compareClassifier)) {
                        ArtifactItem item = createArtifactItem(dependency.getGroupId(), dependency.getArtifactId(), dependency.getType(), dependency.getVersion(), dependency.getClassifier());
                        Artifact artifact = getArtifact(item);
                        // Ignore test-scoped artifacts, by design
                        if (!"test".equals(artifact.getScope())) {
                            log.debug("Found resolved dependency from project dependencyManagement " + dependency.getGroupId() + ":"
                            + dependency.getArtifactId() + ":" + dependency.getVersion());
                            resolvedDependencies.add(artifact);
                            findTransitiveDependencies(artifact, getProject().getArtifacts(), resolvedDependencies);
                        }
                    }
                }
            }

            if (resolvedDependencies.isEmpty()) {
                // No matching artifacts were found in the resolved dependencies. Send warning.
                log.warn("Unable to find artifact matching groupId "+ groupId +", artifactId "+artifactId+" of any version in either project dependencies or in project dependencyManagement (note test-scoped dependencies are excluded).");
            }
        }

        return resolvedDependencies;
     }

    protected static boolean isMatchingProjectDependency(Dependency dependency, String compareGroupId, boolean isWildcard, String compareArtifactId, boolean isClassifierWildcard, String compareClassifier) {
        return isMatchingProjectDependency(dependency.getArtifactId(), dependency.getGroupId(), dependency.getClassifier() != null, dependency.getClassifier(), compareGroupId, isWildcard, compareArtifactId, isClassifierWildcard, compareClassifier);
    }

    protected static boolean isMatchingProjectDependency(Artifact artifact, String compareGroupId, boolean isWildcard, String compareArtifactId, boolean isClassifierWildcard, String compareClassifier) {
        return isMatchingProjectDependency(artifact.getArtifactId(), artifact.getGroupId(), artifact.hasClassifier(), artifact.getClassifier(), compareGroupId, isWildcard, compareArtifactId, isClassifierWildcard, compareClassifier);
    }

    /**
     * Compares a project artifact with the copyDependency coordinates.
     *
     * @param artifactId           the project artifactId to compare.
     * @param compareGroupId       the compareGroupId of the requested dependency to compare.
     * @param isArtifactIdWildcard whether the artifactId contains a wildcard.
     * @param compareArtifactId    the artifactId to compare. If {@code isArtifactIdWildcard} is {@code true},
     *                             then this will be treated as {@code startsWith} string.
     *                             If this parameter is {@code null}, then this is considered a compareGroupId-only based match.
     * @param isClassifierWildcard whether the original classifier contained a wildcard.
     * @param compareClassifier           The optional artifact compareClassifier of the copyDependency coordinate. May be an empty string or {@code null}.
     * @return {@code true} if the given project artifact matches the given and prepared copyDependency parameters.
     * @throws NullPointerException if {@code projectArtifact} or {@code compareGroupId} is {@code null}.
     */
    protected static boolean isMatchingProjectDependency(String artifactId,
                                                         String groupId,
                                                         boolean hasClassifier,
                                                         String classifier,
                                                         String compareGroupId,
                                                         boolean isArtifactIdWildcard, String compareArtifactId, boolean isClassifierWildcard, String compareClassifier) {
        requireNonNull(artifactId, "artifactId");
        requireNonNull(groupId, "groupId");
        requireNonNull(compareGroupId, "compareGroupId");

        if (!groupId.equals(compareGroupId)) {
            return false;
        }
        if (compareArtifactId == null && compareClassifier == null) {
            // wildcards trimmed to null
            return true;
        }
        boolean artifactIdMatches = isMatchingArtifactId(artifactId, isArtifactIdWildcard, compareArtifactId);
        boolean classifierMatches = isMatchingClassifier(hasClassifier, classifier, isClassifierWildcard, compareClassifier);

        return artifactIdMatches && classifierMatches;
    }

    private static boolean isMatchingClassifier(boolean hasClassifier, String classifier, boolean isClassifierWildcard, String compareClassifier) {
        if (isClassifierWildcard && compareClassifier == null) {
            // wildcards trimmed to null
            return true;
        }

        if (isClassifierWildcard && hasClassifier && classifier.startsWith(compareClassifier)) {
            return true;
        }

        if (hasClassifier) {
            return classifier.equals(compareClassifier);
        }

        return compareClassifier == null;
    }

    private static boolean isMatchingArtifactId(String artifactId, boolean isArtifactIdWildcard, String compareArtifactId) {
        if (compareArtifactId == null) {
            // wildcards trimmed to null
            return true;
        }
        if (isArtifactIdWildcard && artifactId.startsWith(compareArtifactId)) {
            // wildcard trimmed to 'startsWith'
            return true;
        }

        // exact match of artifactId required as no wildcard was specified.
        return artifactId.equals(compareArtifactId);
    }

    protected void findTransitiveDependencies(Artifact resolvedArtifact, Set<Artifact> resolvedArtifacts, Set<Artifact> resolvedDependencies) {
        boolean isProvidedScopeAllowed = resolvedArtifact.getScope().equals(Artifact.SCOPE_PROVIDED);
        String coords = resolvedArtifact.getGroupId() + ":" + resolvedArtifact.getArtifactId() + ":";
        for (Artifact artifact : resolvedArtifacts) {
            // Do not copy transitive dependencies with SCOPE_PROVIDED unless the resolvedArtifact is SCOPE_PROVIDED.
            boolean isProvidedScope = artifact.getScope().equals(Artifact.SCOPE_PROVIDED);
            if (!artifact.equals(resolvedArtifact) && (!isProvidedScope || isProvidedScopeAllowed)) {
                List<String> depTrail = artifact.getDependencyTrail();
                if (dependencyTrailContainsArtifact(coords, resolvedArtifact.getVersion(), depTrail)) {
                    log.info("Adding transitive dependency with scope: "+artifact.getScope()+" and GAV: "+artifact.getGroupId()+":"+artifact.getArtifactId()+":"+artifact.getVersion());
                    resolvedDependencies.add(artifact);
                }
            }
        }
     }

     protected boolean dependencyTrailContainsArtifact(String gaCoords, String version, List<String> depTrail) {
         for (String nextFullArtifactId : depTrail) {
             if (nextFullArtifactId.startsWith(gaCoords) &&
                 ((version == null) || (version != null && nextFullArtifactId.endsWith(":"+version))) ) {
                 return true;
             }
         }
         return false;
     }

    /**
     * Create a new artifact.
     *
     * @param item  The item to create an artifact for
     * @return      A resolved artifact for the given item.
     *
     * @throws MojoExecutionException   Failed to create artifact
     */
    @Override
    protected Artifact createArtifact(final ArtifactItem item) throws MojoExecutionException {
        assert item != null;
        
        if (item.getVersion() == null) {
            throw new MojoExecutionException("Unable to find artifact without version specified: " + item.getGroupId()
                + ":" + item.getArtifactId() + ":" + item.getVersion() + " in either project dependencies or in project dependencyManagement.");
        }
        
        // if version is a range get the highest available version
        if (item.getVersion().trim().startsWith("[") || item.getVersion().trim().startsWith("(") ) {
            try {
                item.setVersion(resolveVersionRange(item.getGroupId(), item.getArtifactId(), item.getType(), item.getVersion()));
            } catch (VersionRangeResolutionException e) {
                throw new MojoExecutionException("Could not get the highest version from the range: " + item.getVersion(), e);
            }
        }
        
        return resolveArtifactItem(item);
    }
    
    private Artifact resolveFromProjectDependencies(ArtifactItem item) {
        Set<Artifact> actifacts = getProject().getArtifacts();
        
        for (Artifact artifact : actifacts) {
            if (artifact.getGroupId().equals(item.getGroupId()) && 
                artifact.getArtifactId().equals(item.getArtifactId()) && 
                artifact.getType().equals(item.getType())) {
                log.debug("Found ArtifactItem from project dependencies: " + artifact.getGroupId() + ":"
                        + artifact.getArtifactId() + ":" + artifact.getVersion());
                // if (!artifact.getVersion().equals(item.getVersion())) {
                // item.setVersion(artifact.getVersion());
                // }
                return artifact;
            }
        }
        
        log.debug(item.getGroupId() + ":" + item.getArtifactId() + ":" + item.getVersion()
                + " is not found from project dependencies.");
        return null;
    }
    
    private Dependency resolveFromProjectDepMgmt(ArtifactItem item) {
        // if project has dependencyManagement section
        if (getProject().getDependencyManagement() != null) {
            List<Dependency> list = getProject().getDependencyManagement().getDependencies();
            
            for (Dependency dependency : list) {
                if (dependency.getGroupId().equals(item.getGroupId()) && 
                    dependency.getArtifactId().equals(item.getArtifactId()) && 
                    dependency.getType().equals(item.getType())) {
                    log.debug("Found ArtifactItem from project dependencyManagement " + dependency.getGroupId() + ":"
                            + dependency.getArtifactId() + ":" + dependency.getVersion());
                    return dependency;
                }
            }
        }
        log.debug(item.getGroupId() + ":" + item.getArtifactId() + ":" + item.getVersion()
                + " is not found from project dependencyManagement.");
        return null;
    }
    
    private Artifact resolveArtifactItem(final ArtifactItem item) throws MojoExecutionException {
        org.eclipse.aether.artifact.Artifact aetherArtifact = new org.eclipse.aether.artifact.DefaultArtifact(
                item.getGroupId(), item.getArtifactId(), item.getType(), item.getVersion());
        
        File artifactFile = resolveArtifactFile(aetherArtifact);
        
        Artifact artifact = new DefaultArtifact(item.getGroupId(), item.getArtifactId(), item.getVersion(),
                Artifact.SCOPE_PROVIDED, item.getType(), item.getClassifier(), new DefaultArtifactHandler("jar"));
        
        
        if (artifactFile != null && artifactFile.exists()) {
        	String pathToLocalArtifact = this.repoSession.getLocalRepositoryManager().getPathForLocalArtifact(aetherArtifact);
            File localArtifactFile = new File (this.artifactRepository.getBasedir() ,pathToLocalArtifact);
            
            //sometimes variable artifactFile has a path of a build output folder(target). Setting the artifact file path that corresponds to Maven coord.
            if(localArtifactFile.exists()) {
            	artifact.setFile(localArtifactFile);
            }else {
            	artifact.setFile(artifactFile);
            }   
            artifact.setResolved(true);
            log.debug(item.getGroupId() + ":" + item.getArtifactId() + ":" + item.getVersion()
                    + " is resolved from project repositories.");
        } else {
            getLog().warn("Artifact " + item.getGroupId() + ":" + item.getArtifactId() + ":" + item.getVersion()
                    + " has no attached file.");
            artifact.setResolved(false);
        }
        return artifact;
    }
    
    private File resolveArtifactFile(org.eclipse.aether.artifact.Artifact aetherArtifact) throws MojoExecutionException {
        ArtifactRequest req = new ArtifactRequest().setRepositories(this.repositories).setArtifact(aetherArtifact);
        ArtifactResult resolutionResult = null;

        try {
            resolutionResult = this.repositorySystem.resolveArtifact(this.repoSession, req);
            if (!resolutionResult.isResolved()) {
                throw new MojoExecutionException("Unable to resolve artifact: " + aetherArtifact.getGroupId() + ":"
                        + aetherArtifact.getArtifactId() + ":" + aetherArtifact.getVersion());
            }
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException("Unable to resolve artifact: " + aetherArtifact.getGroupId() + ":"
                    + aetherArtifact.getArtifactId() + ":" + aetherArtifact.getVersion(), e);
        }
        
        File artifactFile = resolutionResult.getArtifact().getFile();
        
        return artifactFile;
    }
    
    private String resolveVersionRange(String groupId, String artifactId, String extension, String version)
            throws VersionRangeResolutionException {
        org.eclipse.aether.artifact.Artifact aetherArtifact = new org.eclipse.aether.artifact.DefaultArtifact(groupId,
                artifactId, extension, version);
        
        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact(aetherArtifact);
        rangeRequest.setRepositories(repositories);
        
        VersionRangeResult rangeResult = this.repositorySystem.resolveVersionRange(this.repoSession, rangeRequest);
        
        if (rangeResult == null || rangeResult.getHighestVersion() == null) {
            throw new VersionRangeResolutionException(rangeResult, "Unable to resolve version range from " + groupId
                    + ":" + artifactId + ":" + extension + ":" + version);
        }
        getLog().debug("Available versions: " + rangeResult.getVersions());
        return rangeResult.getHighestVersion().toString();
    }
}
