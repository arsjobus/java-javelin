package com.javelin.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Keeps the dependency list short, pinned and reviewed. Adding a dependency means editing this allow-list in the same
 * change, which puts the decision in front of a reviewer instead of letting it slip in with a feature.
 */
class DependencyAuditTest {
    /** groupId:artifactId (scope) for everything the project may depend on directly. */
    private static final Set<String> ALLOWED = Set.of("org.openjfx:javafx-controls (compile)", "org.fxmisc.richtext:richtextfx (compile)", "org.junit.jupiter:junit-jupiter (test)", "com.tngtech.archunit:archunit (test)", "org.slf4j:slf4j-nop (test)");

    private static Document pom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent().strip();
    }

    @Test void onlyReviewedDependenciesAreDeclared() throws Exception {
        Element dependencies = (Element) pom().getDocumentElement().getElementsByTagName("dependencies").item(0);
        Set<String> found = new TreeSet<>();
        NodeList items = dependencies.getElementsByTagName("dependency");
        for (int i = 0; i < items.getLength(); i++) {
            Element d = (Element) items.item(i);
            String scope = text(d, "scope");
            found.add(text(d, "groupId") + ":" + text(d, "artifactId") + " (" + (scope == null ? "compile" : scope) + ")");
        }
        assertEquals(new TreeSet<>(ALLOWED), found);
    }

    @Test void everyVersionIsPinnedExactly() throws Exception {
        Document pom = pom();
        List<String> loose = new ArrayList<>();
        NodeList versions = pom.getElementsByTagName("version");
        for (int i = 0; i < versions.getLength(); i++) {
            String v = versions.item(i).getTextContent().strip();
            if (v.contains("[") || v.contains("(") || v.equalsIgnoreCase("LATEST") || v.equalsIgnoreCase("RELEASE") || v.contains("+")) loose.add(v);
            if (v.endsWith("-SNAPSHOT") && !versions.item(i).getParentNode().getNodeName().equals("project")) loose.add(v);
        }
        assertEquals(List.of(), loose, "floating versions make builds irreproducible and unauditable");
    }

    @Test void noCustomRepositoriesAreConfigured() throws Exception {
        Document pom = pom();
        assertEquals(0, pom.getElementsByTagName("repositories").getLength(), "dependencies come from Maven Central only");
        assertEquals(0, pom.getElementsByTagName("pluginRepositories").getLength());
        assertEquals(0, pom.getElementsByTagName("distributionManagement").getLength());
    }
}
