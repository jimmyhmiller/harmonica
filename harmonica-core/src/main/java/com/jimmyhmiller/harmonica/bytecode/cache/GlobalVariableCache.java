package com.jimmyhmiller.harmonica.bytecode.cache;

/**
 * Cache for {@code GetGlobal} / {@code SetGlobal} instructions.
 *
 * <p>Records the global object's binding slot for a given identifier, plus a
 * version counter that can be invalidated if the global object's binding map
 * changes (delete, configurable redefine, etc.).
 *
 * <p>Mutable. Skeleton.
 */
public final class GlobalVariableCache {

    /** Global environment serial number when this cache was populated. 0 = uninitialized. */
    private long environmentSerialNumber;
    /** Slot index in the global environment's binding storage. */
    private int bindingIndex;
    private boolean inModuleEnvironment;
    private boolean valid;

    public boolean isValid(long currentSerial) {
        return valid && environmentSerialNumber == currentSerial;
    }

    public void install(long serialNumber, int bindingIndex, boolean inModuleEnvironment) {
        this.environmentSerialNumber = serialNumber;
        this.bindingIndex = bindingIndex;
        this.inModuleEnvironment = inModuleEnvironment;
        this.valid = true;
    }

    public void invalidate() { this.valid = false; }

    public int bindingIndex()         { return bindingIndex; }
    public boolean inModuleEnvironment() { return inModuleEnvironment; }
}
