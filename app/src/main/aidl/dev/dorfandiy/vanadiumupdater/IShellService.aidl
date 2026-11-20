package dev.dorfandiy.vanadiumupdater;

import android.os.ParcelFileDescriptor;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
    String install(in ParcelFileDescriptor apk, long size) = 2;
}
