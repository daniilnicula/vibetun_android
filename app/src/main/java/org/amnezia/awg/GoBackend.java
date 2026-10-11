/* SPDX-License-Identifier: Apache-2.0 */
package org.amnezia.awg;

/** Minimal JNI surface for the AmneziaWG userspace engine embedded in GoreBox. */
public final class GoBackend {
    static {
        System.loadLibrary("wg-go");
    }

    private GoBackend() {}

    public static native int awgTurnOn(String interfaceName, int tunFd, String settings);
    public static native void awgTurnOff(int tunnelHandle);
    public static native int awgGetSocketV4(int tunnelHandle);
    public static native int awgGetSocketV6(int tunnelHandle);
    public static native String awgVersion();
}
