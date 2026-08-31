package dev.amirzr.flutter_v2ray_client.v2ray.utils;

import java.io.Serializable;
import java.util.ArrayList;

public class V2rayConfig implements Serializable {

    public String CONNECTED_V2RAY_SERVER_ADDRESS = "";
    public String CONNECTED_V2RAY_SERVER_PORT = "";
    public int LOCAL_SOCKS5_PORT = 10808;
    public int LOCAL_HTTP_PORT = 10809;
    public ArrayList<String> BLOCKED_APPS = null;
    public ArrayList<String> BYPASS_SUBNETS = null;
    public String V2RAY_FULL_JSON_CONFIG = null;
    public boolean ENABLE_TRAFFIC_STATICS = false;
    public String REMARK = "";
    /**
     * Which badvpn UDP implementation tun2socks is started with — see
     * {@code Tun2socksArgs}. Carried in the config under {@code _doft_android.udp_mode}
     * so the old (broken) path can be restored from the server without a rebuild.
     *
     * <p>⚠ THE DEFAULT IS THE FIX. A config that says nothing gets a real SOCKS5 UDP
     * ASSOCIATE, because the value's only job is to turn the fix OFF.
     */
    public String TUN2SOCKS_UDP_MODE = "socks5";
    public String APPLICATION_NAME;
    public String NOTIFICATION_DISCONNECT_BUTTON_NAME;
    public int APPLICATION_ICON;
}
