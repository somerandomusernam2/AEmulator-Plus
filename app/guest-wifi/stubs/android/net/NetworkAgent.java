package android.net;

/**
 * Compile-time stand-in for the hidden framework class (Android 5.0+). It is not packaged: build.sh drops everything under
 * android/ from the jar, the guest framework provides the real class.
 */
public abstract class NetworkAgent {
    /** 5.0 - 11 */
    public NetworkAgent(android.os.Looper looper, android.content.Context context, String logTag, NetworkInfo ni,
                        NetworkCapabilities nc, LinkProperties lp, int score) { }
    /** 11+ (the NetworkInfo constructors are gone from 12) */
    public NetworkAgent(android.content.Context context, android.os.Looper looper, String logTag, NetworkCapabilities nc,
                        LinkProperties lp, int score, NetworkAgentConfig config, NetworkProvider provider) { }
    protected abstract void unwanted();
    public void sendNetworkInfo(NetworkInfo networkInfo) { }
    public Network register() { return null; }
    public void markConnected() { }
    public void unregister() { }
}
