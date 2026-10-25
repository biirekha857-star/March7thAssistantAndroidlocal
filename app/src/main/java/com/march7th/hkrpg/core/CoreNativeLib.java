package com.march7th.hkrpg.core;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

public interface CoreNativeLib extends Library {
    Pointer AsstCreate();

    Pointer AsstCreateEx(AsstApiCallback callback, Pointer customArg);

    void AsstDestroy(Pointer handle);

    int AsstAsyncConnect(Pointer handle, String adbPath, String address, String config, byte block);

    void AsstSetConnectionExtras(String name, String extras);

    int AsstAsyncClick(Pointer handle, int x, int y, byte block);

    int AsstAsyncScreencap(Pointer handle, byte block);

    int AsstAppendTask(Pointer handle, String type, String params);

    byte AsstSetTaskParams(Pointer handle, int id, String params);

    boolean AsstSetUserDir(String path);

    boolean AsstLoadResource(String path);

    boolean AsstSetStaticOption(int key, String value);

    boolean AsstSetInstanceOption(Pointer handle, int key, String value);

    boolean AsstStart(Pointer handle);

    boolean AsstStop(Pointer handle);

    boolean AsstRunning(Pointer handle);

    boolean AsstConnected(Pointer handle);

    boolean AsstBackToHome(Pointer handle);

    long AsstGetImage(Pointer handle, Pointer buff, long bufferSize);

    long AsstGetImageBgr(Pointer handle, Pointer buff, long bufferSize);

    long AsstGetUUID(Pointer handle, Pointer buff, long bufferSize);

    long AsstGetTasksList(Pointer handle, int[] buff, long bufferSize);

    long AsstGetNullSize();

    String AsstGetVersion();

    void AsstLog(String level, String message);
}
