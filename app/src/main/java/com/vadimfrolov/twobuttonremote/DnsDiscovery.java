package com.vadimfrolov.twobuttonremote;

import android.util.Log;

import com.vadimfrolov.twobuttonremote.Network.HardwareAddress;
import com.vadimfrolov.twobuttonremote.Network.HostBean;
import com.vadimfrolov.twobuttonremote.Network.NetInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Based on DnsDiscovery from android-network-discovery
 */

public class DnsDiscovery extends AbstractDiscovery {

    private final static long sChunkSize = 10;
    // number of threads for pool of async tasks.
    private final static int sThreads = 10;
    private ExecutorService mPool;
    private final static int TIMEOUT_SCAN = 3600; // seconds
    private final static int TIMEOUT_SHUTDOWN = 10; // seconds

    public DnsDiscovery(DiscoveryListener discover) {
        super(discover);
    }

    @Override
    protected Void doInBackground(Void... params) {
        if (mDiscover != null) {
            final DiscoveryListener discover = mDiscover.get();
            if (discover != null) {
                int timeout = discover.getTimeout();
                long numChunks = mSize / sChunkSize;
                long numLeft = mSize % sChunkSize;
                mPool = Executors.newFixedThreadPool(sThreads);

                for (long i = 1; i <= numChunks; i++) {
                    long start = mStart-1 + ((i - 1) * sChunkSize + 1);
                    long stop = start + sChunkSize - 1;
                    launch(start, stop, timeout, discover.getGatewayIp());
                }
                if (numLeft > 0) {
                    long start = mStart-1 + (numChunks * sChunkSize + 1);
                    long stop = mEnd;
                    launch(start, stop, timeout, discover.getGatewayIp());
                }
                mPool.shutdown();
                try {
                    if (!mPool.awaitTermination(TIMEOUT_SCAN, TimeUnit.SECONDS)) {
                        mPool.shutdownNow();
                        if (!mPool.awaitTermination(TIMEOUT_SHUTDOWN, TimeUnit.SECONDS)) {
                            Log.i("MyTag", "Pool did not shutdown");
                        }
                    }
                } catch (InterruptedException e) {
                    mPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        return null;
    }

    @Override
    protected void onCancelled() {
        Log.i("MyTag", "We were cancelled");
        if (mPool != null) {
            synchronized (mPool) {
                mPool.shutdown();
            }
        }
        super.onCancelled();
    }

    private void launch(long start, long stop, int timeout, String gatewayIp) {
        if (!mPool.isShutdown()) {
            Log.i("MyTag", "Making new pool " + start + "-" + stop + ", " + NetInfo.getIpFromLongUnsigned(start) + "-" + NetInfo.getIpFromLongUnsigned(stop));
            mPool.execute(new CheckRunnable(start, stop, timeout, gatewayIp));
        }
    }

    private class CheckRunnable implements Runnable {
        private final long mStart;
        private final long mStop;
        private final int mTimeout;
        private final String mGatewayIp;

        CheckRunnable(long start, long stop, int timeout, String gatewayIp) {
            mStart = start;
            mStop = stop;
            mTimeout = timeout;
            mGatewayIp = gatewayIp;
        }

        public void run() {
            if (isCancelled()) {
                publishProgress((HostBean)null);
                return;
            }

            for (long i = mStart; i <= mStop; i++) {
                HostBean host = new HostBean();
                host.hardwareAddress = NetInfo.NOMAC;
                host.hostname = null;
                host.ipAddress = NetInfo.getIpFromLongUnsigned(i);
                host.broadcastIp = null;
                try {
                    InetAddress ia = InetAddress.getByName(host.ipAddress);
                    host.hostname = ia.getCanonicalHostName();
                    host.isAlive = ia.isReachable(mTimeout);
                } catch (java.net.UnknownHostException e) {

                } catch (IOException e) {

                }
                if (host.hostname != null && !host.hostname.equals(host.ipAddress)) {
                    // Is gateway ?
                    if (mGatewayIp.equals(host.ipAddress)) {
                        publishProgress((HostBean) null);
                        continue;
                    }

                    // Mac address
                    host.hardwareAddress = HardwareAddress.getHardwareAddress(host.ipAddress);
                    if (host.hardwareAddress.equals(NetInfo.NOMAC)) {
//                        Log.i("MyTag", i + " (" + host.ipAddress + ") has invalid MAC. Publishing null");
                        publishProgress((HostBean) null);
                    } else {
                        Log.i("MyTag", i + " (" + host.ipAddress + ") seems to be valid. Publishing it");
                        publishProgress(host);
                    }
                } else {
                    publishProgress((HostBean) null);
                }
            }
        }
    }
}
