// ITouchEventCallback.aidl
package com.march7th.hkrpg;

// Declare any non-default types here with import statements

oneway interface ITouchEventCallback {
   void onCallback(int x, int y, int type, int contact);
}