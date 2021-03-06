/**
 * Copyright 1&1 Internet AG, http://www.1and1.org
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.beezle.jasmin.model;

import net.sf.beezle.sushi.metadata.annotation.Type;
import net.sf.beezle.sushi.util.Separator;

import java.util.ArrayList;
import java.util.List;

/** A loaded modul. Has a name, a list of files, and a list of dependencies to other modules. */
@Type
public class Module {
    public static final Separator SEP = Separator.on('+');
    public static final char NOT = '!';

    private final String name;

    private final List<File> files;

    private final List<Module> dependencies;

    private final Source source;

    public Module(String name, Source source) {
        if (name.contains(SEP.getSeparator())) {
            throw new IllegalArgumentException(name);
        }
        if (name.indexOf(NOT) != -1) {
            throw new IllegalArgumentException(name);
        }
        this.name = name;
        this.files = new ArrayList<File>();
        this.dependencies = new ArrayList<Module>();
        this.source = source;
    }

    public Source getSource() {
        return source;
    }

    public String getName() {
        return name;
    }

    public List<File> files() {
        return files;
    }

    public List<Module> dependencies() {
        return dependencies;
    }

    //--

    public List<File> resolve(Request request) {
        List<File> result;
        String actual;
        boolean variantSeen;

        result = new ArrayList<File>();
        variantSeen = false;
        for (File file : files) {
            if (file.getType().equals(request.type)) {
                actual = file.getVariant();
                if (actual == null) {
                    result.add(file);
                } else {
                    variantSeen = true;
                }
            }
        }
        if (variantSeen) {
            addVariant(request.type, getBest(request.type, request.variant), result);
        }
        return result;
    }

    private static final String DELIM = ":";

    private void addVariant(MimeType type, String variant, List<File> result) {
        for (File file : files) {
            if (file.getType().equals(type) && variant.equals(file.getVariant())) {
                result.add(file);
            }
        }
    }

    /** @return a variant */
    public String getBest(MimeType type, String variant) {
        String requested;
        String actual;
        String best;

        requested = variant + DELIM;
        best = null;
        for (File file : files) {
            if (file.getType().equals(type)) {
                actual = file.getVariant();
                if (requested.startsWith(actual + DELIM)) {
                    if (best == null) {
                        best = actual;
                    } else if (actual.length() > best.length()) {
                        best = actual;
                    }
                }
            }
        }
        if (best == null) {
            return "lead";
        }
        return best;
    }

    @Override
    public String toString() {
        return getName();
    }
}
