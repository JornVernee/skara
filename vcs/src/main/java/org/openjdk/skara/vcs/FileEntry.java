/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.vcs;

import java.util.Objects;
import java.nio.file.Path;

public class FileEntry {
    private final Hash commit;
    private final FileType type;
    private final Hash hash;
    private final Path path;

    public FileEntry(Hash commit, FileType type, Hash hash, Path path) {
        this.commit = commit;
        this.type = type;
        this.hash = hash;
        this.path = path;
    }

    public Hash commit() {
        return commit;
    }

    public FileType type() {
        return type;
    }

    public Hash hash() {
        return hash;
    }

    public Path path() {
        return path;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commit, type, hash, path);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileEntry)) {
            return false;
        }

        var e = (FileEntry) o;
        return Objects.equals(commit, e.commit) &&
               Objects.equals(type, e.type) &&
               Objects.equals(hash, e.hash) &&
               Objects.equals(path, e.path);
    }

    @Override
    public String toString() {
        return type.toString() + " blob " + hash.toString() + "\t" + path.toString();
    }
}
