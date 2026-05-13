package com.drivehub.kamera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

final class NetworkStateHelper {

    enum Transport {
        WIFI,
        CELLULAR,
        ETHERNET,
        OFFLINE,
        UNKNOWN
    }

    private NetworkStateHelper() {
    }

    static Transport getActiveTransport(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return Transport.UNKNOWN;
            Network network = cm.getActiveNetwork();
            if (network == null) return Transport.OFFLINE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return Transport.UNKNOWN;
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return Transport.OFFLINE;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Transport.WIFI;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return Transport.CELLULAR;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Transport.ETHERNET;
            return Transport.UNKNOWN;
        } catch (Throwable ignored) {
            return Transport.UNKNOWN;
        }
    }
}
