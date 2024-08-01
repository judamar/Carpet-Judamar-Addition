package uk.co.thinkofdeath.vanillacord.helper;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.network.handshake.client.C00Handshake;

public class BungeeHelper {
    private static final Gson GSON = new Gson();

    static final AttributeKey<UUID> UUID_KEY = NetworkManager.getAttribute("-vch-uuid");

    static final AttributeKey<Property[]> PROPERTIES_KEY = (AttributeKey)NetworkManager.getAttribute("-vch-properties");

    public static void parseHandshake(Object network, Object handshake) {
        try {
            Channel channel = (Channel)NetworkManager.channel.get(network);
            String host = Handshake.getHostName(handshake);
            String[] split = host.split("\000", 5);
            String uuid;
            if ((split.length != 4 && split.length != 3) || (uuid = split[2]).length() != 32)
                throw QuietException.show("If you wish to use IP forwarding, please enable it in your BungeeCord config as well!");
            NetworkManager.socket.set(network, new InetSocketAddress(split[1], ((InetSocketAddress)NetworkManager.socket.get(network)).getPort()));
            channel.attr(UUID_KEY).set(UUID.fromString((new StringBuilder(36))
                    .append(uuid, 0, 8)
                    .append('-').append(uuid, 8, 12)
                    .append('-').append(uuid, 12, 16)
                    .append('-').append(uuid, 16, 20)
                    .append('-').append(uuid, 20, 32)
                    .toString()));
            if ((getSeecret()).length == 0) {
                channel.attr(PROPERTIES_KEY).set((split.length == 3) ? new Property[0] : GSON.fromJson(split[3], Property[].class));
                return;
            }
            if (split.length == 4) {
                Property[] properties = (Property[])GSON.fromJson(split[3], Property[].class);
                if (properties.length != 0) {
                    Property[] modified = new Property[properties.length - 1];
                    int i = 0;
                    boolean found = false;
                    for (Property property : properties) {
                        if ("bungeeguard-token".equals(property.getName())) {
                            if (!(found = (!found && Arrays.binarySearch((Object[])seecret, property.getValue()) >= 0)))
                                break;
                        } else if (i != modified.length) {
                            modified[i++] = property;
                        }
                    }
                    if (found) {
                        channel.attr(PROPERTIES_KEY).set(modified);
                        return;
                    }
                }
            }
            throw QuietException.show("Received invalid IP forwarding data. Did you use the right forwarding secret?");
        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    private static String[] seecret = null;

    private static String[] getSeecret() throws IOException {
        if (seecret == null) {
            File config = new File("seecret.txt");
            if (config.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(config)))) {
                    HashSet<String> tokens = new HashSet<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("bungeeguard-token=") && (line = line.substring(18)).length() != 0)
                            tokens.add(line);
                    }
                    Arrays.sort((Object[])(seecret = (String[])((tokens.size() != 0) ? (Object[])tokens.toArray(new String[0]) : (Object[])new String[0])));
                }
            } else {
                seecret = new String[0];
                PrintWriter writer = new PrintWriter(config, StandardCharsets.UTF_8.name());
                writer.println("# As you may know, standard IP forwarding procedure is to accept connections from any and all proxies.");
                writer.println("# If you'd like to change that, you can do so by entering BungeeGuard tokens here.");
                writer.println("# ");
                writer.println("# This file is automatically generated by VanillaCord once a player attempts to join the server.");
                writer.println();
                writer.println("bungeeguard-token=");
                writer.close();
            }
        }
        return seecret;
    }

    public static GameProfile injectProfile(Object network, GameProfile profile) {
        try {
            Channel channel = (Channel)NetworkManager.channel.get(network);
            GameProfile modified = new GameProfile((UUID)channel.attr(UUID_KEY).get(), profile.getName());
            for (Property property : (Property[])channel.attr(PROPERTIES_KEY).get())
                modified.getProperties().put(property.getName(), property);
            return modified;
        } catch (Exception e) {
            throw exception(null, e);
        }
    }

    static RuntimeException exception(String text, Throwable e) {
        if (e instanceof QuietException)
            return (QuietException)e;
        if (e.getCause() instanceof QuietException)
            return (QuietException)e.getCause();
        if (text != null)
            e = new RuntimeException(text, e);
        e.printStackTrace();
        return new QuietException(e);
    }

    static final class NetworkManager {
        public static final Field channel;

        public static final Field socket;

        static {
            try {
                Class<?> clazz = net.minecraft.network.NetworkManager.class;
                channel = accessFieldByType(clazz, Channel.class);
                socket = accessFieldByType(clazz, SocketAddress.class);
            } catch (Throwable e) {
                throw BungeeHelper.exception("Class generation failed", e);
            }
        }

        public static <T> AttributeKey<T> getAttribute(String key) {
            return AttributeKey.valueOf(key);
        }
    }

    static final class Handshake {
        private static final Field hostName;

        static {
            try {
                Class<?> clazz = C00Handshake.class;
                hostName = accessFieldByType(clazz, String.class);
            } catch (Throwable e) {
                throw BungeeHelper.exception("Class generation failed", e);
            }
        }

        public static String getHostName(Object instance) throws Exception {
            return (String)hostName.get(instance);
        }
    }

    static Field accessFieldByType(Class<?> clazz, Class<?> type) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new AssertionError("Field lookup failed: Type " + type + " in class " + clazz);
    }
}