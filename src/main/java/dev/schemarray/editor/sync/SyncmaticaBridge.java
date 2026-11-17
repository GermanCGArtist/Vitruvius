package dev.schemarray.editor.sync;

import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Syncmatica seen through reflection only (mod id {@code syncmatica}, checked against 0.3.20 for Minecraft 26.1.x,
 * package {@code ch.endte.syncmatica}); Litematica Array has no compile dependency on it, so with the mod missing or
 * changed nothing here is ever loaded and {@link #available()} says why.
 *
 * <p>What is driven (all on the client thread):
 * <ul>
 *   <li>{@code LitematicManager.getInstance().getActiveContext()}: the client {@code Context}, null off a Syncmatica server;
 *   <li>{@code context.getCommunicationManager().getServer()}: the {@code ExchangeTarget} of the server;
 *   <li>share: {@code LitematicManager.syncmaticFromSchematic(placement)} gives the {@code ServerPlacement} (its id is
 *       fixed there), then {@code new ShareLitematicExchange(placement, server, context, serverPlacement)} is started
 *       with {@code communicationManager.startExchange}; {@code Exchange.isFinished/isSuccessful} report the end;
 *   <li>remove: a {@code REMOVE_SYNCMATIC} packet with the placement id, sent with {@code ExchangeTarget.sendPacket}
 *       (what Syncmatica's own Remove button does);
 *   <li>list: {@code context.getSyncmaticManager().getAll()}, read through {@code ServerPlacement.getId/getName/getFileName};
 *   <li>download: {@code communicationManager.download(serverPlacement, server)}; the local file comes from
 *       {@code context.getFileStorage().getLocalState/getLocalLitematic}.
 * </ul>
 */
public final class SyncmaticaBridge {
    private SyncmaticaBridge() {}

    public static final String MOD_ID = "syncmatica";
    private static final String P = "ch.endte.syncmatica.";

    private static boolean initDone;
    private static String failure;

    private static Method litematicGetInstance, activeContext, comMan, getServer, syncFromSchematic, startExchange, sendPacket, synMan, getAll,
            spId, spName, spFileName, spHash, download, fileStorage, localState, localLitematic, isFinished, isSuccessful, fileReady, isSyncmatic;
    private static java.lang.reflect.Constructor<?> shareCtor;
    private static Class<?> clientComManClass, packetTypeClass;
    private static Object removePacket;

    /** Whether the mod is installed (no class is touched). */
    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Whether the classes and methods this bridge needs were found. */
    public static synchronized boolean available() {
        if (!installed()) return false;
        if (!initDone) init();
        return failure == null;
    }

    /** Why {@link #available()} is false although the mod is installed, else null. */
    public static String failure() {
        return installed() ? failure : "Syncmatica is not installed";
    }

    private static void init() {
        initDone = true;
        try {
            Class<?> manager = Class.forName(P + "litematica.LitematicManager");
            Class<?> context = Class.forName(P + "Context");
            Class<?> placement = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
            Class<?> serverPlacement = Class.forName(P + "data.ServerPlacement");
            Class<?> target = Class.forName(P + "communication.ExchangeTarget");
            Class<?> comManager = Class.forName(P + "communication.CommunicationManager");
            Class<?> exchange = Class.forName(P + "communication.exchange.Exchange");
            Class<?> share = Class.forName(P + "communication.exchange.ShareLitematicExchange");
            Class<?> storage = Class.forName(P + "data.IFileStorage");
            Class<?> state = Class.forName(P + "data.LocalLitematicState");
            clientComManClass = Class.forName(P + "communication.ClientCommunicationManager");
            packetTypeClass = Class.forName(P + "network.PacketType");
            litematicGetInstance = manager.getMethod("getInstance");
            activeContext = manager.getMethod("getActiveContext");
            syncFromSchematic = manager.getMethod("syncmaticFromSchematic", placement);
            isSyncmatic = manager.getMethod("isSyncmatic", placement);
            comMan = context.getMethod("getCommunicationManager");
            synMan = context.getMethod("getSyncmaticManager");
            fileStorage = context.getMethod("getFileStorage");
            getServer = clientComManClass.getMethod("getServer");
            startExchange = comManager.getMethod("startExchange", exchange);
            download = comManager.getMethod("download", serverPlacement, target);
            sendPacket = target.getMethod("sendPacket", packetTypeClass, FriendlyByteBuf.class, context);
            shareCtor = share.getConstructor(placement, target, context, serverPlacement);
            getAll = Class.forName(P + "data.SyncmaticManager").getMethod("getAll");
            spId = serverPlacement.getMethod("getId");
            spName = serverPlacement.getMethod("getName");
            spFileName = serverPlacement.getMethod("getFileName");
            spHash = serverPlacement.getMethod("getHash");
            localState = storage.getMethod("getLocalState", serverPlacement);
            localLitematic = storage.getMethod("getLocalLitematic", serverPlacement);
            fileReady = state.getMethod("isLocalFileReady");
            isFinished = exchange.getMethod("isFinished");
            isSuccessful = exchange.getMethod("isSuccessful");
            removePacket = enumConstant(packetTypeClass, "REMOVE_SYNCMATIC");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            failure = "Syncmatica's API is not what this version expects (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    // ------------------------------------------------------------------------------------------ connection

    private static Object context() throws ReflectiveOperationException {
        return activeContext.invoke(litematicGetInstance.invoke(null));
    }

    private static Object serverTarget(Object ctx) throws ReflectiveOperationException {
        Object cm = comMan.invoke(ctx);
        return clientComManClass.isInstance(cm) ? getServer.invoke(cm) : null;
    }

    /** Whether Syncmatica is on a server right now (a client context and a server target exist). */
    public static boolean connected() {
        if (!available()) return false;
        try {
            Object ctx = context();
            return ctx != null && serverTarget(ctx) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------ share and remove

    /**
     * Whether Syncmatica already tracks this Litematica placement as a shared one. Such a placement cannot be shared
     * again (Syncmatica hands back the old placement id, which the server refuses); the caller replaces the placement
     * object first.
     */
    public static boolean isSyncmatic(Object litematicaPlacement) {
        try {
            return (Boolean) isSyncmatic.invoke(litematicGetInstance.invoke(null), litematicaPlacement);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** A started share: the exchange, and the Syncmatica placement id fixed before it started. */
    public record Handle(Object exchange, UUID id) {}

    /**
     * Starts sharing a Litematica placement.
     *
     * @param litematicaPlacement a {@code SchematicPlacement}
     * @throws IllegalStateException when there is no connection or Syncmatica refuses to describe the placement
     */
    public static Handle share(Object litematicaPlacement) throws ReflectiveOperationException {
        Object ctx = context();
        Object server = ctx == null ? null : serverTarget(ctx);
        if (server == null) throw new IllegalStateException("Not connected to a server with Syncmatica");
        Object sp = syncFromSchematic.invoke(litematicGetInstance.invoke(null), litematicaPlacement);
        if (sp == null) throw new IllegalStateException("Syncmatica cannot share this placement");
        Object exchange = shareCtor.newInstance(litematicaPlacement, server, ctx, sp);
        startExchange.invoke(comMan.invoke(ctx), exchange);
        return new Handle(exchange, (UUID) spId.invoke(sp));
    }

    public static boolean finished(Handle h) {
        try {
            return (Boolean) isFinished.invoke(h.exchange());
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    public static boolean successful(Handle h) {
        try {
            return (Boolean) isSuccessful.invoke(h.exchange());
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Removes a shared placement from the server (what Syncmatica's Remove button sends). */
    public static void remove(UUID id) throws ReflectiveOperationException {
        Object ctx = context();
        Object server = ctx == null ? null : serverTarget(ctx);
        if (server == null) throw new IllegalStateException("Not connected to a server with Syncmatica");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(id);
        sendPacket.invoke(server, removePacket, buf, ctx);
    }

    // ------------------------------------------------------------------------------------------ list and download

    /** One placement the server holds. {@code placement} is Syncmatica's own object (opaque here). */
    public record Remote(UUID id, String name, String fileName, UUID hash, Object placement) {}

    /** Everything Syncmatica currently lists (empty off a server). */
    public static List<Remote> list() {
        List<Remote> out = new ArrayList<>();
        if (!available()) return out;
        try {
            Object ctx = context();
            if (ctx == null) return out;
            for (Object sp : (Collection<?>) getAll.invoke(synMan.invoke(ctx))) {
                out.add(new Remote((UUID) spId.invoke(sp), (String) spName.invoke(sp), (String) spFileName.invoke(sp), (UUID) spHash.invoke(sp), sp));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the list is empty when Syncmatica is not ready
        }
        return out;
    }

    /** The manifests among {@link #list()}. */
    public static List<Remote> manifests() {
        List<Remote> out = new ArrayList<>();
        for (Remote r : list()) if (SyncManifest.isManifest(r.name())) out.add(r);
        return out;
    }

    public static Remote find(UUID id) {
        for (Remote r : list()) if (r.id().equals(id)) return r;
        return null;
    }

    /** The downloaded file of a placement, or null when it is not on this computer yet. */
    public static Path localFile(Remote r) {
        try {
            Object ctx = context();
            if (ctx == null) return null;
            Object storage = fileStorage.invoke(ctx);
            if (!(Boolean) fileReady.invoke(localState.invoke(storage, r.placement()))) return null;
            Object p = localLitematic.invoke(storage, r.placement());
            return p instanceof Path path && Files.isRegularFile(path) ? path : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Starts downloading a placement. Returns false when it is already here (nothing to do) or cannot start; Syncmatica
     * refuses a second download of the same file while one runs, which is reported as {@code false} too.
     */
    public static boolean startDownload(Remote r) {
        try {
            Object ctx = context();
            Object server = ctx == null ? null : serverTarget(ctx);
            if (server == null || localFile(r) != null) return false;
            download.invoke(comMan.invoke(ctx), r.placement(), server);
            return true;
        } catch (InvocationTargetException e) {
            return false;       // already downloading, or the local state forbids it
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
