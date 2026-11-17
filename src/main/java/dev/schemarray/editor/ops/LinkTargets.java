package dev.schemarray.editor.ops;

import dev.schemarray.editor.io.ProjectLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Link paths on the file port's level (strings relative to the schematics folder, '/' separators). The rules are
 * those of {@code mc.LinkPaths}: a link is relative to the folder of the linking project and may use {@code ..};
 * what the parts' files of the linked file are relative to is the linking project when the file is inside it, else
 * the project that owns the file ({@code Projects/<name>}), else the file's own folder.
 */
final class LinkTargets {
    private LinkTargets() {}

    /** Collapses {@code .} and {@code ..}; null when the path would leave the root. */
    static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        return String.join("/", out);
    }

    /** The port path a link of an assembly in the project at {@code baseDir} points to, or null when it leaves the port. */
    static String target(String baseDir, String link) {
        return normalize(FileSystemPort.join(baseDir, link));
    }

    /** The folder the parts' files of the file at {@code target} are relative to. */
    static String base(String baseDir, String target) {
        String b = normalize(baseDir);
        if (b != null && (b.isEmpty() || target.equals(b) || target.startsWith(b + "/"))) return b;
        String[] seg = target.split("/");
        for (int i = 0; i + 2 < seg.length; i++) {
            if (seg[i].equals(ProjectLayout.PROJECTS_DIR)) return seg[i] + "/" + seg[i + 1];
        }
        return FileSystemPort.dirOf(target);
    }

    /** The link path from folder {@code fromDir} to {@code to} (both normalised port paths). */
    static String relative(String fromDir, String to) {
        String[] a = fromDir.isEmpty() ? new String[0] : fromDir.split("/");
        String[] b = to.isEmpty() ? new String[0] : to.split("/");
        int i = 0;
        while (i < a.length && i < b.length - 1 && a[i].equals(b[i])) i++;
        StringBuilder sb = new StringBuilder();
        for (int k = i; k < a.length; k++) sb.append("../");
        for (int k = i; k < b.length; k++) sb.append(b[k]).append(k + 1 < b.length ? "/" : "");
        return sb.toString();
    }
}
