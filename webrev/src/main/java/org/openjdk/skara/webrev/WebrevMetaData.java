/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.webrev;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebrevMetaData {
    private static final Pattern findPatchPattern = Pattern.compile(
            "<a href=\".*\">(?<patchName>.*\\.patch)</a>");

    private static final Pattern findChangeSetPattern = Pattern.compile(
            "<a href=\".*\">(?<changeSetName>.*\\.changeset)</a>");

    private static final Pattern summaryPattern = Pattern.compile(
            "(?<linesChanged>\\d+) lines? changed:" +
                    " (?<insertions>\\d+) ins;" +
                    " (?<deletions>\\d+) del;" +
                    " (?<modifications>\\d+) mod;" +
                    " (?<unchanged>\\d+) unchg"
    );

    private static final Pattern findHeaderValuePattern = Pattern.compile(
            "<tr>\\s*<th>\\n?(?<key>.*):\\n?</th>\\s*<td>\\n?(?<value>.*)\\n?</td>\\s*</tr>");

    private final URI webrevURI;

    private final Optional<String> branch;
    private final Optional<String> author;
    private final Optional<WebrevStats> summary;
    private final Optional<String> workspace;
    private final Optional<URI> repository;
    private final Optional<String> compareAgainst;
    private final Optional<String> compareAgainstVersion;
    private final Optional<String> compareAgainstRevision;
    private final Optional<URI> patchURI;
    private final Optional<URI> changesetURI;

    public WebrevMetaData(URI webrevURI, Optional<String> branch, Optional<String> author,
                          Optional<WebrevStats> summary, Optional<String> workspace, Optional<URI> repository,
                          Optional<String> compareAgainst, Optional<String> compareAgainstVersion,
                          Optional<String> compareAgainstRevision, Optional<URI> patchURI, Optional<URI> changesetURI) {
        this.webrevURI = webrevURI;
        this.branch = branch;
        this.author = author;
        this.summary = summary;
        this.workspace = workspace;
        this.repository = repository;
        this.compareAgainst = compareAgainst;
        this.compareAgainstVersion = compareAgainstVersion;
        this.compareAgainstRevision = compareAgainstRevision;
        this.patchURI = patchURI;
        this.changesetURI = changesetURI;
    }

    public static WebrevMetaData fromWebrevURL(String uri) throws IOException, URISyntaxException, InterruptedException {
        var sanitizedUri = sanitizeURI(uri);
        var client = HttpClient.newHttpClient();
        var findPatchFileRequest = HttpRequest.newBuilder()
                .uri(sanitizedUri)
                .build();
        var header = client.send(findPatchFileRequest, HttpResponse.BodyHandlers.ofLines())
                .body()
                .dropWhile(s -> !s.startsWith("<table>"))
                .takeWhile(s -> !s.startsWith("</table>"))
                .map(findHeaderValuePattern::matcher)
                .filter(Matcher::find)
                .collect(Collectors.toMap(m -> m.group("key"), m -> m.group("value")));

        augmentHeaderFromURL(header, uri);

        var patchURI = Optional.ofNullable(header.get("Patch of changes"))
                .map(findPatchPattern::matcher)
                .filter(Matcher::find)
                .map(m -> m.group("patchName"))
                .map(sanitizedUri::resolve);

        var changesetURI = Optional.ofNullable(header.get("Changeset"))
                .map(findChangeSetPattern::matcher)
                .filter(Matcher::find)
                .map(m -> m.group("changeSetName"))
                .map(sanitizedUri::resolve);

        var author = Optional.ofNullable(header.get("Prepared by"))
                .map(s -> s.split(" ")[0]);

        var branch = Optional.ofNullable(header.get("Branch"));

        var summary = Optional.ofNullable(header.get("Summary of changes"))
                .flatMap(WebrevMetaData::parseSummary);

        var workspace = Optional.ofNullable(header.get("Workspace"));

        var repository = Optional.ofNullable(header.get("Repository"))
                .map(URI::create);

        var compareAgainst = Optional.ofNullable(header.get("Compare against"));
        var compareAgainstVersion = Optional.ofNullable(header.get("Compare against version"));
        var compareAgainstRevision = Optional.ofNullable(header.get("Compare against revision"));

        return new WebrevMetaData(sanitizedUri, branch, author, summary, workspace, repository, compareAgainst,
                                  compareAgainstVersion, compareAgainstRevision, patchURI, changesetURI);
    }

    private static void augmentHeaderFromURL(Map<String, String> header, String uri) {
        // TODO parse URL and add values to header (if not already present)
    }

    private static Optional<WebrevStats> parseSummary(String s) {
        var matcher = summaryPattern.matcher(s);
        if (matcher.find()) {
            return Optional.of(new WebrevStats(
                Integer.parseInt(matcher.group("insertions")),
                Integer.parseInt(matcher.group("deletions")),
                Integer.parseInt(matcher.group("modifications")),
                Integer.parseInt(matcher.group("linesChanged"))
            ));
        }
        return Optional.empty();
    }

    private static String dropSuffix(String s, String suffix) {
        if (s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    private static URI sanitizeURI(String uri) throws URISyntaxException {
        uri = dropSuffix(uri, "index.html");
        return new URI(uri);
    }

    public URI webrevURI() {
        return webrevURI;
    }

    public Optional<String> branch() {
        return branch;
    }

    public Optional<String> author() {
        return author;
    }

    public Optional<WebrevStats> summary() {
        return summary;
    }

    public Optional<String> workspace() {
        return workspace;
    }

    public Optional<URI> repository() {
        return repository;
    }

    public Optional<String> compareAgainst() {
        return compareAgainst;
    }

    public Optional<String> compareAgainstVersion() {
        return compareAgainstVersion;
    }

    public Optional<String> compareAgainstRevision() {
        return compareAgainstRevision;
    }

    public Optional<URI> patchURI() {
        return patchURI;
    }

    public Optional<URI> changesetURI() {
        return changesetURI;
    }
}
