package org.embeddedt.modernfix.dynamicresources;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import net.minecraft.client.resources.model.ModelBaker;
import org.joml.Vector3fc;

// Currently just mirrors vanilla behaviour, I don't think it needs to do anything else?
class DynamicPartCache implements ModelBaker.PartCache {
    private final Interner<Vector3fc> vectors = Interners.newStrongInterner();

    public Vector3fc vector(Vector3fc vector3fc) {
        return this.vectors.intern(vector3fc);
    }
}