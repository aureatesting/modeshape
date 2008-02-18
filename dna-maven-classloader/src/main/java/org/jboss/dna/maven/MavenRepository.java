/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.dna.maven;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.jcr.Repository;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.jboss.dna.common.util.ArgCheck;
import org.jboss.dna.common.util.Logger;
import org.jboss.dna.common.xml.SimpleNamespaceContext;
import org.jboss.dna.maven.spi.IMavenUrlProvider;
import org.jboss.dna.maven.spi.JcrMavenUrlProvider;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A Maven 2 repository that can be used to store and access artifacts like JARs and source archives within a running application.
 * This class understands Maven 2 Project Object Model (POM) files, and thus is able to analyze dependencies and provide a
 * {@link ClassLoader class loader} that accesses libraries using these transitive dependencies.
 * <p>
 * Instances are initialized with an authenticated {@link IMavenUrlProvider Maven URL provider}, which is typically a
 * {@link JcrMavenUrlProvider} instance configured with a {@link Repository JCR Repository} and path to the root of the repository
 * subtree in that workspace. The repository can either already exist and contain the required artifacts, or it will be created as
 * artifacts are loaded. Then to use libraries that are in the repository, simply obtain the
 * {@link #getClassLoader(ClassLoader,MavenId...) class loader} by specifying the {@link MavenId artifact identifiers} for the
 * libraries used directly by your code. This class loader will add any libraries that are required by those you supply.
 * </p>
 * @author Randall Hauch
 */
public class MavenRepository {

    private final IMavenUrlProvider urlProvider;
    private final MavenClassLoaders classLoaders;
    private final Logger logger;

    public MavenRepository( final IMavenUrlProvider urlProvider ) {
        if (urlProvider == null) throw new IllegalArgumentException("The URL provider reference may not be null");
        this.urlProvider = urlProvider;
        this.classLoaders = new MavenClassLoaders(this);
        this.logger = Logger.getLogger(this.getClass());
        assert this.logger != null;
        assert this.urlProvider != null;
    }

    /**
     * Get a class loader that has as its classpath the JARs for the libraries identified by the supplied IDs. This method always
     * returns a class loader, even when none of the specified libraries {@link #exists(MavenId) exist} in this repository.
     * @param parent the parent class loader that will be consulted before any project class loaders; may be null if the system
     * class loader should be used
     * @param mavenIds the IDs of the libraries in this Maven repository
     * @return the class loader
     * @see #exists(MavenId)
     * @see #exists(MavenId,MavenId...)
     * @throws IllegalArgumentException if no Maven IDs are passed in or if any of the IDs are null
     */
    public ClassLoader getClassLoader( ClassLoader parent, MavenId... mavenIds ) {
        ArgCheck.isNotEmpty(mavenIds, "mavenIds");
        ArgCheck.containsNoNulls(mavenIds, "mavenIds");
        return this.classLoaders.getClassLoader(parent, mavenIds);
    }

    /**
     * Determine whether the identified library exists in this Maven repository.
     * @param mavenId the ID of the library
     * @return true if this repository contains the library, or false if it does not exist (or the ID is null)
     * @throws MavenRepositoryException if there is a problem connecting to or using the Maven repository, as configured
     * @see #exists(MavenId,MavenId...)
     */
    public boolean exists( MavenId mavenId ) throws MavenRepositoryException {
        if (mavenId == null) return false;
        Set<MavenId> existing = exists(mavenId, (MavenId)null);
        return existing.contains(mavenId);
    }

    /**
     * Determine which of the identified libraries exist in this Maven repository.
     * @param firstId the first ID of the library to check
     * @param mavenIds the IDs of the libraries; any null IDs will be ignored
     * @return the set of IDs for libraries that do exist in this repository; never null
     * @throws MavenRepositoryException if there is a problem connecting to or using the Maven repository, as configured
     * @see #exists(MavenId)
     */
    public Set<MavenId> exists( MavenId firstId, MavenId... mavenIds ) throws MavenRepositoryException {
        if (mavenIds == null || mavenIds.length == 0) return Collections.emptySet();

        // Find the set of MavenIds that are not null ...
        Set<MavenId> nonNullIds = new HashSet<MavenId>();
        if (firstId != null) nonNullIds.add(firstId);
        for (MavenId mavenId : mavenIds) {
            if (mavenId != null) nonNullIds.add(mavenId);
        }
        if (nonNullIds.isEmpty()) return nonNullIds;

        MavenId lastMavenId = null;
        try {
            for (Iterator<MavenId> iter = nonNullIds.iterator(); iter.hasNext();) {
                lastMavenId = iter.next();
                URL urlToMavenId = this.urlProvider.getUrl(lastMavenId, null, null, false);
                boolean exists = urlToMavenId != null;
                if (!exists) iter.remove();
            }
        } catch (MalformedURLException err) {
            throw new MavenRepositoryException("Unable to create a URL given the supplied Maven ID " + lastMavenId + ": " + err.getMessage());
        }
        return nonNullIds;
    }

    /**
     * Get the dependencies for the Maven project with the specified ID.
     * <p>
     * This implementation downloads the POM file for the specified project to extract the dependencies and exclusions.
     * </p>
     * @param mavenId the ID of the project; may not be null
     * @return the list of dependencies
     * @throws IllegalArgumentException if the MavenId reference is null
     * @throws MavenRepositoryException if there is a problem finding or reading the POM file given the MavenId
     */
    public List<MavenDependency> getDependencies( MavenId mavenId ) {
        URL pomUrl = null;
        try {
            pomUrl = getUrl(mavenId, ArtifactType.POM, null);
            return getDependencies(mavenId, pomUrl.openStream());
        } catch (IOException e) {
            String msg = "Error getting the POM file for " + mavenId + " at " + pomUrl;
            throw new MavenRepositoryException(msg, e);
        }
    }

    /**
     * Get the dependencies for the Maven project with the specified ID.
     * <p>
     * This implementation downloads the POM file for the specified project to extract the dependencies and exclusions.
     * </p>
     * @param mavenId the ID of the Maven project for which the dependencies are to be obtained
     * @param pomStream the stream to the POM file
     * @param allowedScopes the set of scopes that are to be allowed in the dependency list; if null, the default scopes of
     * {@link MavenDependency.Scope#getRuntimeScopes()} are used
     * @return the list of dependencies; never null
     * @throws IllegalArgumentException if the MavenId or InputStream references are null
     * @throws IOException if an error occurs reading the stream
     * @throws MavenRepositoryException if there is a problem reading the POM file given the supplied stream and MavenId
     */
    protected List<MavenDependency> getDependencies( MavenId mavenId, InputStream pomStream, MavenDependency.Scope... allowedScopes ) throws IOException {
        if (mavenId == null) throw new IllegalArgumentException("The mavenId parameter may not be null");
        if (pomStream == null) throw new IllegalArgumentException("The pomStream parameter may not be null");
        EnumSet<MavenDependency.Scope> includedScopes = MavenDependency.Scope.getRuntimeScopes();
        if (allowedScopes != null && allowedScopes.length > 0) includedScopes = EnumSet.of(allowedScopes[0], allowedScopes);
        List<MavenDependency> results = new ArrayList<MavenDependency>();

        try {
            // Use JAXP to load the XML document ...
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); // never forget this!
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomStream);

            // Create an XPath object ...
            XPathFactory xpathFactory = XPathFactory.newInstance();
            XPath xpath = xpathFactory.newXPath();
            xpath.setNamespaceContext(new SimpleNamespaceContext().setNamespace("m", "http://maven.apache.org/POM/4.0.0"));

            // Set up some XPath expressions ...
            XPathExpression projectExpression = xpath.compile("//m:project");
            XPathExpression dependencyExpression = xpath.compile("//m:project/m:dependencies/m:dependency");
            XPathExpression groupIdExpression = xpath.compile("./m:groupId/text()");
            XPathExpression artifactIdExpression = xpath.compile("./m:artifactId/text()");
            XPathExpression versionExpression = xpath.compile("./m:version/text()");
            XPathExpression classifierExpression = xpath.compile("./m:classifier/text()");
            XPathExpression scopeExpression = xpath.compile("./m:scope/text()");
            XPathExpression typeExpression = xpath.compile("./m:type/text()");
            XPathExpression exclusionExpression = xpath.compile("./m:exclusions/m:exclusion");

            // Extract the Maven ID for this POM file ...
            org.w3c.dom.Node projectNode = (org.w3c.dom.Node)projectExpression.evaluate(doc, XPathConstants.NODE);
            String groupId = (String)groupIdExpression.evaluate(projectNode, XPathConstants.STRING);
            String artifactId = (String)artifactIdExpression.evaluate(projectNode, XPathConstants.STRING);
            String version = (String)versionExpression.evaluate(projectNode, XPathConstants.STRING);
            String classifier = (String)classifierExpression.evaluate(projectNode, XPathConstants.STRING);
            if (groupId == null || artifactId == null || version == null) {
                throw new IllegalArgumentException("Unable to find groupId, artifactId and/or version in POM file for " + mavenId);
            }
            MavenId actualMavenId = new MavenId(groupId, artifactId, version, classifier);
            if (!mavenId.equals(actualMavenId)) {
                throw new IllegalArgumentException("The POM file is for " + actualMavenId + " but was expected to be " + mavenId);
            }

            // Evaluate the XPath expression and iterate over the "dependency" nodes ...
            NodeList nodes = (NodeList)dependencyExpression.evaluate(doc, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); ++i) {
                org.w3c.dom.Node dependencyNode = nodes.item(i);
                assert dependencyNode != null;
                String depGroupId = (String)groupIdExpression.evaluate(dependencyNode, XPathConstants.STRING);
                String depArtifactId = (String)artifactIdExpression.evaluate(dependencyNode, XPathConstants.STRING);
                String depVersion = (String)versionExpression.evaluate(dependencyNode, XPathConstants.STRING);
                String depClassifier = (String)classifierExpression.evaluate(dependencyNode, XPathConstants.STRING);
                String scopeText = (String)scopeExpression.evaluate(dependencyNode, XPathConstants.STRING);
                String depType = (String)typeExpression.evaluate(dependencyNode, XPathConstants.STRING);

                // Extract the Maven dependency ...
                if (depGroupId == null || depArtifactId == null || depVersion == null) {
                    this.logger.debug("Skipping dependency of {1} due to missing groupId, artifactId or version: {2}", mavenId, dependencyNode);
                    continue; // not enough information, so skip
                }
                MavenDependency dependency = new MavenDependency(depGroupId, depArtifactId, depVersion, depClassifier);
                dependency.setType(depType);

                // If the scope is "compile" (default) or "runtime", then we need to process the dependency ...
                dependency.setScope(scopeText);
                if (!includedScopes.contains(dependency.getScope())) continue;

                // Find any exclusions ...
                NodeList exclusionNodes = (NodeList)exclusionExpression.evaluate(dependencyNode, XPathConstants.NODESET);
                for (int j = 0; j < exclusionNodes.getLength(); ++j) {
                    org.w3c.dom.Node exclusionNode = exclusionNodes.item(j);
                    assert exclusionNode != null;
                    String excludedGroupId = (String)groupIdExpression.evaluate(exclusionNode, XPathConstants.STRING);
                    String excludedArtifactId = (String)artifactIdExpression.evaluate(exclusionNode, XPathConstants.STRING);

                    if (excludedGroupId == null || excludedArtifactId == null) {
                        this.logger.debug("Skipping exclusion in dependency of {1} due to missing exclusion groupId or artifactId: {2} ", mavenId, exclusionNode);
                        continue; // not enough information, so skip
                    }
                    MavenId excludedId = new MavenId(excludedGroupId, excludedArtifactId);
                    dependency.getExclusions().add(excludedId);
                }

                results.add(dependency);
            }
        } catch (XPathExpressionException err) {
            String msg = "Error creating XPath statements to evaluate the POM file for " + mavenId;
            throw new MavenRepositoryException(msg, err);
        } catch (ParserConfigurationException err) {
            String msg = "Error creating the XPath parser when evaluating the POM file for " + mavenId;
            throw new MavenRepositoryException(msg, err);
        } catch (SAXException err) {
            String msg = "Error reading the XML document when evaluating the POM file for " + mavenId;
            throw new MavenRepositoryException(msg, err);
        } finally {
            try {
                pomStream.close();
            } catch (IOException e) {
                String msg = "Error closing the URL stream to " + mavenId;
                this.logger.error(e, msg);
            }
        }
        return results;
    }

    /**
     * Get the URL for the artifact with the specified type in the given Maven project. The resulting URL can be used to
     * {@link URL#openConnection() connect} to the repository to {@link URLConnection#getInputStream() read} or
     * {@link URLConnection#getOutputStream() write} the artifact's content.
     * @param mavenId the ID of the Maven project; may not be null
     * @param artifactType the type of artifact; may be null, but the URL will not be able to be read or written to
     * @param signatureType the type of signature; may be null if the signature file is not desired
     * @return the URL to this artifact; never null
     * @throws MalformedURLException if the supplied information cannot be turned into a valid URL
     */
    public URL getUrl( MavenId mavenId, ArtifactType artifactType, SignatureType signatureType ) throws MalformedURLException {
        return this.urlProvider.getUrl(mavenId, artifactType, signatureType, false);
    }

    /**
     * Get the URL for the artifact with the specified type in the given Maven project. The resulting URL can be used to
     * {@link URL#openConnection() connect} to the repository to {@link URLConnection#getInputStream() read} or
     * {@link URLConnection#getOutputStream() write} the artifact's content.
     * @param mavenId the ID of the Maven project; may not be null
     * @param artifactType the type of artifact; may be null, but the URL will not be able to be read or written to
     * @param signatureType the type of signature; may be null if the signature file is not desired
     * @param createIfRequired true if the node structure should be created if any part of it does not exist; this always expects
     * that the path to the top of the repository tree exists.
     * @return the URL to this artifact; never null
     * @throws MalformedURLException if the supplied information cannot be turned into a valid URL
     */
    public URL getUrl( MavenId mavenId, ArtifactType artifactType, SignatureType signatureType, boolean createIfRequired ) throws MalformedURLException {
        return this.urlProvider.getUrl(mavenId, artifactType, signatureType, createIfRequired);
    }

    /**
     * This method is called to signal this repository that the POM file for a project has been updated. This method notifies the
     * associated class loader of the change, which will adapt appropriately.
     * @param mavenId
     */
    protected void notifyUpdatedPom( MavenId mavenId ) {
        this.classLoaders.notifyChangeInDependencies(mavenId);
    }
}
