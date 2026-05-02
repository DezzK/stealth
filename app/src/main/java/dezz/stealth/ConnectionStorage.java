/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists every successful connection endpoint (host, port, transport) found by the
 * last full discovery so the next discovery cycle can re-verify them first and reach
 * a usable {@code activeFactory} much faster than a cold port scan.
 */
public class ConnectionStorage {
    private static final String KEY_ENDPOINTS = "endpoints";
    private static final String DELIM = "|";

    public static final String TRANSPORT_ADB = "ADB";
    public static final String TRANSPORT_TELNET = "Telnet";

    private final SharedPreferences prefs;

    public ConnectionStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(
                context.getPackageName() + "_connection", Context.MODE_PRIVATE);
    }

    public static class Endpoint {
        public final String host;
        public final int port;
        public final String transport;

        public Endpoint(String host, int port, String transport) {
            this.host = host;
            this.port = port;
            this.transport = transport;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Endpoint)) return false;
            Endpoint e = (Endpoint) o;
            return port == e.port && host.equals(e.host) && transport.equals(e.transport);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port * 17 + transport.hashCode();
        }
    }

    public List<Endpoint> loadAll() {
        Set<String> raw = prefs.getStringSet(KEY_ENDPOINTS, null);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        List<Endpoint> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            Endpoint e = decode(s);
            if (e != null) out.add(e);
        }
        return out;
    }

    public void saveAll(Collection<Endpoint> endpoints) {
        Set<String> raw = new HashSet<>();
        for (Endpoint e : endpoints) {
            raw.add(encode(e));
        }
        prefs.edit().putStringSet(KEY_ENDPOINTS, raw).commit();
    }

    public void clear() {
        prefs.edit().clear().commit();
    }

    private static String encode(Endpoint e) {
        return e.host + DELIM + e.port + DELIM + e.transport;
    }

    private static Endpoint decode(String s) {
        String[] parts = s.split("\\" + DELIM, -1);
        if (parts.length != 3) return null;
        try {
            int port = Integer.parseInt(parts[1]);
            return new Endpoint(parts[0], port, parts[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
