package dev.vitruvius.editor.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which blocks of an object are removed in the world (EDITOR-2.0 section 5). Pure: block occupancy of other objects
 * comes from an {@link Occupancy} that the Minecraft side builds from a loaded schematic and its world transform.
 *
 * <p>A plan lists the contributors of one placed copy of an object (its base cell, an array cell, or a part of a linked
 * assembly):
 * <ul>
 *   <li>{@link Kind#TRIM}: an owner (object or assembly) with a better front value whose boxes overlap this copy.
 *       Where it has a non-air block, this copy loses its block (a partly filled winner trims only where it is solid);
 *   <li>{@link Kind#DIFFERENCE}: an enabled Boolean modifier: removed where the target is non-air;
 *   <li>{@link Kind#INTERSECT}: an enabled Boolean modifier: kept only where the target is non-air.
 * </ul>
 * Disabled modifiers, wire objects (as losers or as winners of a trim), hidden nodes and Heavymatica/External nodes
 * never contribute. The {@link Plan#hash} depends only on the source file (name and modification time) and, for each
 * contributor, the modification times, operation and transforms <em>relative to the copy</em> of every placed copy it
 * brings. It is the same for the same inputs, changes when any of them does, and is the same for two copies of an
 * array that are surrounded the same way, so they can share one derived file.
 */
public final class MaskPlan {
    private MaskPlan() {}

    /** Whether the world block at a position is a non-air block of some placed schematic. */
    @FunctionalInterface
    public interface Occupancy {
        boolean solid(int x, int y, int z);
    }

    public enum Kind { TRIM, DIFFERENCE, INTERSECT }

    /** One placed copy that a contributor brings: its file, the file's modification time, world transform and world box. */
    public record Placed(Path file, long mtime, Transform world, Box box) {}

    /** What a plan needs from the outside: file lookup, modification times and (lazy) occupancy. */
    public interface Env extends AssemblyBounds.Env {
        /** The file of an object whose files are relative to {@code base} (null = the model's rule), or null. */
        Path file(ObjectNode o, Path base);

        /** Modification time of the file in ms, 0 when it cannot be read. */
        long mtime(Path file);

        /** The occupancy of the union of these placed copies; may load files lazily. */
        Occupancy occupancy(List<Placed> placed);
    }

    /** One source of removals. {@code id} is the winner (TRIM) or the Boolean target. */
    public record Contributor(Kind kind, UUID id, List<Placed> placed, Occupancy occupancy) {
        public Contributor {
            placed = List.copyOf(placed);
        }

        /** Whether the block position lies in the box of any placed copy. */
        public boolean inBoxes(int x, int y, int z) {
            Vec3i p = new Vec3i(x, y, z);
            for (Placed pl : placed) if (pl.box().contains(p)) return true;
            return false;
        }

        /** The contributor as seen from a copy: its copies at transforms relative to it, so the text is position-independent. */
        String signature(Transform ownInverse) {
            List<String> parts = new ArrayList<>();
            for (Placed p : placed) parts.add(p.mtime() + "@" + canon(ownInverse.then(p.world())));
            parts.sort(null);
            return kind + parts.toString();
        }
    }

    /** What to include in a plan. */
    public record Filter(boolean trims, boolean booleans, int onlyModifier) {
        public static final Filter ALL = new Filter(true, true, -1);

        /** Only the Boolean modifier at {@code index} (for Apply). */
        public static Filter onlyBoolean(int index) {
            return new Filter(false, true, index);
        }
    }

    /** The plan of one placed copy. Empty when nothing is removed (use the source file). */
    public record Plan(UUID objectId, long sourceMtime, Transform world, List<Contributor> contributors, String hash) {
        public Plan {
            contributors = List.copyOf(contributors);
        }

        public boolean isEmpty() {
            return contributors.isEmpty();
        }

        /** Whether the block at this world position is removed. */
        public boolean removes(int x, int y, int z) {
            return remover(x, y, z) != null;
        }

        /** The first contributor that removes the block at this world position, or null when it stays. */
        public Contributor remover(int x, int y, int z) {
            for (Contributor c : contributors) {
                switch (c.kind()) {
                    case TRIM, DIFFERENCE -> {
                        if (c.inBoxes(x, y, z) && c.occupancy().solid(x, y, z)) return c;
                    }
                    case INTERSECT -> {
                        if (!c.inBoxes(x, y, z) || !c.occupancy().solid(x, y, z)) return c;
                    }
                }
            }
            return null;
        }
    }

    public static Plan emptyPlan(UUID objectId) {
        return new Plan(objectId, 0, Transform.IDENTITY, List.of(), "");
    }

    /**
     * What is planned: one placed copy of an object (its base cell, an array cell, or a part of an array cell or a
     * linked assembly). {@code base} is what the object's file is relative to (null: the model's rule), {@code world}
     * the copy's world transform and {@code owner} the node whose front order counts (the object itself, or the
     * topmost assembly above it). {@code loose} marks a Loose object (no project folder).
     */
    public record Subject(ObjectNode object, Path base, Transform world, Node owner, boolean loose) {}

    /** The placed copies of winners and cutters, shared by the copies of one planning pass. */
    public static final class Cache {
        private final Map<UUID, List<Placed>> all = new HashMap<>();
    }

    /**
     * Plans the base cell of one object of the model (Apply, exports, the Properties numbers). Wire objects, hidden or
     * missing ones and unreadable files give an empty plan.
     */
    public static Plan plan(Scene scene, ObjectNode target, Collection<? extends HasFront> owners, Env env, Filter f) {
        Subject sub = new Subject(target, null, scene.worldTransform(target), scene.ownerOf(target), scene.projectOf(target) == null);
        return plan(scene, sub, owners, env, f, new Cache());
    }

    /**
     * Plans one placed copy. Trims need an owner with a better front value whose boxes overlap this copy; for a Loose
     * object only owners with an explicit front order count (an unnumbered pile of old placements is never trimmed by
     * accident).
     *
     * @param owners the front owners of the world ({@code Scene.frontOwnersInWorld}), the candidates for trims
     */
    public static Plan plan(Scene scene, Subject sub, Collection<? extends HasFront> owners, Env env, Filter f, Cache cache) {
        ObjectNode target = sub.object();
        UUID id = target.id();
        Node ownOwner = sub.owner();
        if (target.wire() || target.missing() || hiddenChain(target) || hiddenChain(ownOwner)) return emptyPlan(id);
        Path file = env.file(target, sub.base());
        Vec3i size = env.size(target, sub.base());
        if (file == null || size == null || size.x() <= 0 || size.y() <= 0 || size.z() <= 0) return emptyPlan(id);
        long mtime = env.mtime(file);
        if (mtime <= 0) return emptyPlan(id);
        Transform own = sub.world();
        Box ownBox = Box.ofSize(size).transformed(own);
        int ownFront = ownOwner instanceof HasFront h ? h.front() : 0;

        List<Contributor> cs = new ArrayList<>();
        if (f.trims()) {
            for (HasFront w : owners) {
                if (!(w instanceof Node wn) || wn.id().equals(ownOwner.id())) continue;
                if (wn instanceof ObjectNode wo ? wo.wire() || wo.missing() : !(wn instanceof AssemblyNode)) continue;
                if (hiddenChain(wn)) continue;
                if (FrontOrder.compare(w.front(), wn.id(), ownFront, ownOwner.id()) >= 0) continue;
                if (sub.loose() && w.front() == 0) continue;
                List<Placed> placed = placedOf(scene, wn, env, ownBox, cache);
                if (!placed.isEmpty()) cs.add(new Contributor(Kind.TRIM, wn.id(), placed, env.occupancy(placed)));
            }
        }
        if (f.booleans()) {
            List<Modifier> mods = target.mods();
            for (int i = 0; i < mods.size(); i++) {
                if (!(mods.get(i) instanceof BooleanModifier bm) || !bm.enabled || bm.targetId == null) continue;
                if (f.onlyModifier() >= 0 && f.onlyModifier() != i) continue;
                Node t = scene.byId(bm.targetId);
                if (!(t instanceof ObjectNode || t instanceof AssemblyNode) || t == target || isAncestor(t, target) || isAncestor(target, t)) continue;
                boolean intersect = bm.op == BooleanModifier.Op.INTERSECT;
                // a cutter that does not touch the object removes nothing (difference) and is left out; an intersect keeps every copy
                List<Placed> placed = placedOf(scene, t, env, intersect ? null : ownBox, cache);
                if (placed.isEmpty()) continue;   // an unreadable or hidden target is ignored, never "remove everything"
                cs.add(new Contributor(intersect ? Kind.INTERSECT : Kind.DIFFERENCE, t.id(), placed, env.occupancy(placed)));
            }
        }
        if (cs.isEmpty()) return emptyPlan(id);
        return new Plan(id, mtime, own, cs, hash(mtime, file.getFileName().toString(), own, cs));
    }

    /** The placed copies of a node (arrays and parts included); with {@code within} only those whose box overlaps it. */
    private static List<Placed> placedOf(Scene scene, Node n, Env env, Box within, Cache cache) {
        List<Placed> all = cache.all.get(n.id());
        if (all == null) {
            Transform parent = n.parent() == null ? Transform.IDENTITY : scene.worldTransform(n.parent());
            all = new ArrayList<>();
            for (BakeParts.Part p : BakeParts.collect(List.of(n), parent, null, env)) {
                if (p.object().wire()) continue;
                Path file = env.file(p.object(), p.base());
                Vec3i size = env.size(p.object(), p.base());
                if (file == null || size == null || size.x() <= 0 || size.y() <= 0 || size.z() <= 0) continue;
                long mt = env.mtime(file);
                if (mt <= 0) continue;
                all.add(new Placed(file, mt, p.world(), Box.ofSize(size).transformed(p.world())));
            }
            cache.all.put(n.id(), all);
        }
        if (within == null) return all;
        List<Placed> out = new ArrayList<>();
        for (Placed p : all) if (!within.intersect(p.box()).isEmpty()) out.add(p);
        return out;
    }

    /**
     * The stable hash of the inputs (16 hex digits): the source file and, per contributor, its operation and the
     * modification time and transform of every placed copy relative to this copy. It does not depend on where the copy
     * is, only on how the others lie around it.
     */
    public static String hash(long sourceMtime, String sourceName, Transform own, List<Contributor> contributors) {
        Transform inv = own.inverse();
        List<String> sigs = new ArrayList<>();
        for (Contributor c : contributors) sigs.add(c.signature(inv));
        sigs.sort(null);
        String text = "own:" + sourceName + "@" + sourceMtime + "|" + String.join("|", sigs);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canon(Transform t) {
        Transform c = t.canonical();
        return c.pos().x() + "," + c.pos().y() + "," + c.pos().z() + "/" + c.rot().steps() + "/" + c.mirror();
    }

    private static boolean hiddenChain(Node n) {
        for (Node x = n; x != null; x = x.parent()) if (x.hidden()) return true;
        return false;
    }

    private static boolean isAncestor(Node anc, Node n) {
        for (Node x = n.parent(); x != null; x = x.parent()) if (x == anc) return true;
        return false;
    }
}
