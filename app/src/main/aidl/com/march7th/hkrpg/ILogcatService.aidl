package com.march7th.hkrpg;

interface ILogcatService {
    oneway void destroy() = 16777114;
    void startCapture(int appPid, int servicePid, String userDir) = 1;
}
