package dev.vitruvius.api;

import com.google.gson.JsonObject;
import dev.vitruvius.editor.core.Transform;

import java.util.UUID;

/**
 * One placed copy of an object of your type, for exports (Heavymatica's Manhattan export): its node id, name, the
 * array cell it is ({@code "base"} or "i,j,k"), its world transform and a copy of its data. Hidden objects are
 * included with {@code hidden = true}.
 */
public record SceneObjectView(UUID id, String name, String cell, Transform world, JsonObject data, boolean hidden) {}
