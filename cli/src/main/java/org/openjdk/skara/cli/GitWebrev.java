/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.webrev.*;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public class GitWebrev {
    private static void clearDirectory(Path directory) {
        try {
            Files.walk(directory)
                    .map(Path::toFile)
                    .sorted(Comparator.reverseOrder())
                    .forEach(File::delete);
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
    }

    private static String arg(String name, Arguments args, ReadOnlyRepository repo) throws IOException {
        if (args.contains(name)) {
            return args.get(name).asString();
        }

        var config = repo.config("webrev." + name);
        if (config.size() == 1) {
            return config.get(0);
        }

        return null;
    }

    private static void die(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static Hash resolve(ReadOnlyRepository repo, String ref) {
        var message = "error: could not resolve reference '" + ref + "'";
        try {
            var hash = repo.resolve(ref);
            if (!hash.isPresent()) {
                die(message);
            }
            return hash.get();
        } catch (IOException e) {
            die(message);
            return null; // impossible
        }
    }

    private static void generate(String[] args) throws IOException {
        var flags = List.of(
            Option.shortcut("r")
                  .fullname("rev")
                  .describe("REV")
                  .helptext("Compare against a specified revision")
                  .optional(),
            Option.shortcut("o")
                  .fullname("output")
                  .describe("DIR")
                  .helptext("Output directory")
                  .optional(),
            Option.shortcut("u")
                  .fullname("username")
                  .describe("NAME")
                  .helptext("Use that username instead of 'guessing' one")
                  .optional(),
            Option.shortcut("")
                  .fullname("repository")
                  .describe("URL")
                  .helptext("The URL to the upstream repository")
                  .optional(),
            Option.shortcut("t")
                  .fullname("title")
                  .describe("TITLE")
                  .helptext("The title of the webrev")
                  .optional(),
            Option.shortcut("c")
                  .fullname("cr")
                  .describe("CR#")
                  .helptext("Include link to CR (aka bugid) in the main page")
                  .optional(),
            Switch.shortcut("b")
                  .fullname("")
                  .helptext("Do not ignore changes in whitespace (always true)")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("C")
                  .fullname("no-comments")
                  .helptext("Don't show comments")
                  .optional(),
            Switch.shortcut("N")
                  .fullname("no-outgoing")
                  .helptext("Do not compare against remote, use only 'status'")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var parser = new ArgumentParser("git webrev", flags);
        var arguments = parser.parse(args);

        var version = Version.fromManifest().orElse("unknown");
        if (arguments.contains("version")) {
            System.out.println("git-webrev version: " + version);
            System.exit(0);
        }

        var repo = getRepo();
        var isMercurial = arguments.contains("mercurial");

        var upstream = arg("repository", arguments, repo);
        if (upstream == null) {
            try {
                var remote = isMercurial ? "default" : "origin";
                var pullPath = repo.pullPath(remote);
                var uri = new URI(pullPath);
                var host = uri.getHost();
                var path = uri.getPath();
                if (host != null && path != null) {
                    if (host.equals("github.com") && path.startsWith("/openjdk/")) {
                        upstream = "https://github.com" + path;
                    } else if (host.equals("openjdk.java.net")) {
                        upstream = "https://openjdk.java.net" + path;
                    }
                }
            } catch (URISyntaxException e) {
                // do nothing
            }
        }

        var noOutgoing = arguments.contains("no-outgoing");
        if (!noOutgoing) {
            var config = repo.config("webrev.no-outgoing");
            if (config.size() == 1) {
                var enabled = Set.of("TRUE", "ON", "1", "ENABLED");
                noOutgoing = enabled.contains(config.get(0).toUpperCase());
            }
        }

        var rev = arguments.contains("rev") ?
            resolve(repo, arguments.get("rev").asString()) :
            noOutgoing ?
                resolve(repo, isMercurial ? "tip" : "HEAD") :
                resolve(repo, isMercurial ? "min(outgoing())^" : "origin" + "/" + "master");

        var issue = arguments.contains("cr") ? arguments.get("cr").asString() : null;
        if (issue != null && !issue.startsWith("http")) {
            var digits = Set.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
            if (digits.contains(issue.charAt(0))) {
                issue = "JDK-" + issue;
            }
            issue = "https://bugs.openjdk.java.net/browse/" + issue;
        }
        if (issue == null) {
            var pattern = Pattern.compile("(?:(JDK|CODETOOLS|JMC)-)?([0-9]+).*");
            var branch = repo.currentBranch().name().toUpperCase();
            var m = pattern.matcher(branch);
            if (m.matches()) {
                var project = m.group(1);
                if (project == null) {
                    project = "JDK";
                }
                var id = m.group(2);
                issue = "https://bugs.openjdk.java.net/browse/" + project + "-" + id;
            }
        }

        var out = arg("output", arguments, repo);
        if (out == null) {
            out = "webrev";
        }
        var output = Path.of(out);

        var title = arguments.contains("title") ?
            arguments.get("title").asString() : null;
        if (title == null && issue != null) {
            try {
                var uri = new URI(issue);
                title = Path.of(uri.getPath()).getFileName().toString();
            } catch (URISyntaxException e) {
                title = null;
            }
        }
        if (title == null && upstream != null) {
            var index = upstream.lastIndexOf("/");
            if (index != -1 && index + 1 < upstream.length()) {
                title = upstream.substring(index + 1);
            }
        }
        if (title == null) {
            title = Path.of("").toAbsolutePath().getFileName().toString();
        }

        var username = arg("username", arguments, repo);
        if (username == null) {
            username = repo.username().orElse(System.getProperty("user.name"));
        }

        if (Files.exists(output)) {
            clearDirectory(output);
        }

        Webrev.repository(repo)
              .output(output)
              .title(title)
              .upstream(upstream)
              .username(username)
              .issue(issue)
              .version(version)
              .generate(rev);
    }

    private static void apply(String[] args) throws Exception {
        var inputs = List.of(
            Input.position(0)
                .describe("webrev url")
                .singular()
                .required());

        var parser = new ArgumentParser("git webrev apply", List.of(), inputs);
        var arguments = parser.parse(args);

        var repository = getRepo();

        var inputString = arguments.at(0).asString();
        var webrevMetaData = WebrevMetaData.fromWebrevURL(inputString);
        var patchFileURI = webrevMetaData.patchURI()
                .orElseThrow(() -> new IllegalStateException("Could not find patch file in webrev"));
        var patchFile = downloadPatchFile(patchFileURI);

        repository.apply(patchFile, false);
    }

    private static Path downloadPatchFile(URI uri) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var patchFile = Files.createTempFile("patch", ".patch");
        var patchFileRequest = HttpRequest.newBuilder()
                .uri(uri)
                .build();
        client.send(patchFileRequest, HttpResponse.BodyHandlers.ofFile(patchFile));
        return patchFile;
    }

    private static void fetch(String[] args) throws Exception {
        var flags = List.of(
                Option.shortcut("b")
                      .fullname("branch")
                      .describe("BRANCH")
                      .helptext("the name of the branch of the new branch to create. (default is WEBREV_FETCH_HEAD)")
                      .optional(),
                Option.shortcut("")
                      .fullname("ref")
                      .describe("REFSPEC")
                      .helptext("ref to which to apply this webrev")
                      .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("webrev url")
                 .singular()
                 .required());

        var parser = new ArgumentParser("git webrev apply", flags, inputs);
        var arguments = parser.parse(args);

        var repo = getRepo();
        if (!repo.isClean()) {
            throw new IllegalStateException("Repo is not clean");
        }

        var branch = arguments.contains("branch")
               ? arguments.get("branch").asString()
               : "WEBREV_FETCH_HEAD";

        var webrevMetdaData = WebrevMetaData.fromWebrevURL(arguments.at(0).asString());
        var patchURI = webrevMetdaData.patchURI()
                .orElseThrow(() -> new IllegalStateException("Could not find patch file in webrev"));
        var patch = downloadPatchFile(patchURI);

        Hash targetHash;
        if (arguments.contains("ref")) {
            targetHash = resolve(repo, arguments.get("ref").asString());
        } else if (webrevMetdaData.compareAgainstRevision().isPresent()) { // hash
            targetHash = new Hash(webrevMetdaData.compareAgainstRevision().get());
        } else if (webrevMetdaData.branch().isPresent()) {
            var onto = webrevMetdaData.branch().get();
            if (repo.branches().stream().map(Branch::name).anyMatch(onto::equals)) {
                targetHash = resolve(repo, onto);
            } else {
                throw new IllegalStateException(
                        "Webrev applies to branch '" + onto + "', but this repo has no such branch");
            }
        } else {
            throw new IllegalStateException(
                    "Found no information indicating where to apply this webrev." +
                    "Use --ref to specify ref explicitly");
        }
        var newBranch = repo.branch(targetHash, branch);
        repo.checkout(newBranch);
        repo.apply(patch, false);
        repo.commit(arguments.at(0).asString(), webrevMetdaData.author().orElse(""), "");
    }

    private static Repository getRepo() throws IOException {
        var cwd = Paths.get("").toAbsolutePath();
        return Repository.get(cwd).orElseGet(() -> {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
            return null;
        });
    }

    public static void main(String[] args) throws Exception {
        var commands = List.of(
                    Default.name("generate")
                           .helptext("generate a webrev")
                           .main(GitWebrev::generate),
                    Command.name("apply")
                           .helptext("apply a webrev from a webrev url")
                           .main(GitWebrev::apply),
                    Command.name("fetch")
                           .helptext("apply a webrev as a commit to a separate branch")
                           .main(GitWebrev::fetch)
                );

        var parser = new MultiCommandParser("git webrev", commands);
        var command = parser.parse(args);
        command.execute();
    }
}
