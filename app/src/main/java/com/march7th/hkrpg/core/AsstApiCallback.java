package com.march7th.hkrpg.core;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

public interface AsstApiCallback extends Callback {
    void invoke(int msg, String json, Pointer customArg);
}