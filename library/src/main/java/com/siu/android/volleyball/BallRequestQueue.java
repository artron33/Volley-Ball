/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.siu.android.volleyball;

import android.os.Handler;
import android.os.Looper;

import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.Request;
import com.android.volley.VolleyLog;
import com.siu.android.volleyball.ball.BallExecutorDelivery;
import com.siu.android.volleyball.local.LocalDispatcher;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request dispatch queue with a thread pool of dispatchers.
 * <p/>
 * Calling {@link #add(BallRequest)} will enqueue the given Request for dispatch,
 * resolving from either cache or network on a worker thread, and then delivering
 * a parsed response on the main thread.
 */
@SuppressWarnings("rawtypes")
public class BallRequestQueue { //extends RequestQueue {

    /**
     * Used for generating monotonically-increasing sequence numbers for requests.
     */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     * <p/>
     * <ul>
     * <li>containsKey(cacheKey) indicates that there is a request in flight for the given cache
     * key.</li>
     * <li>get(cacheKey) returns waiting requests for the given cache key. The in flight request
     * is <em>not</em> contained in that list. Is null if no requests are staged.</li>
     * </ul>
     */
    private final Map<String, Queue<BallRequest>> mWaitingRequests =
            new HashMap<String, Queue<BallRequest>>();

    /**
     * The set of all requests currently being processed by this RequestQueue. A Request
     * will be in this set if it is waiting in any queue or currently being processed by
     * any dispatcher.
     */
    private final Set<BallRequest> mCurrentRequests = new HashSet<BallRequest>();

    /**
     * The cache triage queue.
     */
    private final PriorityBlockingQueue<BallRequest> mCacheQueue =
            new PriorityBlockingQueue<BallRequest>();

    /**
     * The queue of requests that are actually going out to the network.
     */
    private final PriorityBlockingQueue<BallRequest> mNetworkQueue =
            new PriorityBlockingQueue<BallRequest>();

    private final PriorityBlockingQueue<BallRequest> mLocalQueue =
            new PriorityBlockingQueue<BallRequest>();

    /**
     * Number of network request dispatcher threads to start.
     */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /**
     * Cache interface for retrieving and storing respones.
     */
    private final Cache mCache;

    /**
     * Network interface for performing requests.
     */
    private final Network mNetwork;

    /**
     * Response delivery mechanism.
     */
    private final BallResponseDelivery mDelivery;

    /**
     * The network dispatchers.
     */
    private BallNetworkDispatcher[] mDispatchers;

    /**
     * The cache dispatcher.
     */
    private BallCacheDispatcher mCacheDispatcher;

    private LocalDispatcher mLocalDispatcher;

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache          A Cache to use for persisting responses to disk
     * @param network        A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     */
    public BallRequestQueue(Cache cache, Network network, int threadPoolSize) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new BallNetworkDispatcher[threadPoolSize];
        mDelivery = new BallExecutorDelivery(new Handler(Looper.getMainLooper()), mNetworkQueue);
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache   A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     */
    public BallRequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    /**
     * Starts the dispatchers in this queue.
     */
    public void start() {
        stop();  // Make sure any currently running dispatchers are stopped.
        // Create the cache dispatcher and start it.
        mCacheDispatcher = new BallCacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // Create network dispatchers (and corresponding threads) up to the pool size.
        for (int i = 0; i < mDispatchers.length; i++) {
            BallNetworkDispatcher networkDispatcher = new BallNetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }

        // local dispatcher
        mLocalDispatcher = new LocalDispatcher(mLocalQueue, mNetworkQueue, mDelivery);
        mLocalDispatcher.start();
    }

    /**
     * Stops the cache and network dispatchers.
     */
    public void stop() {
        if (mCacheDispatcher != null) {
            mCacheDispatcher.quit();
        }
        for (int i = 0; i < mDispatchers.length; i++) {
            if (mDispatchers[i] != null) {
                mDispatchers[i].quit();
            }
        }
        if (mLocalDispatcher != null) {
            mLocalDispatcher.quit();
        }
    }

    /**
     * Gets a sequence number.
     */
    public int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }

    /**
     * Gets the {@link com.android.volley.Cache} instance being used.
     */
    public Cache getCache() {
        return mCache;
    }

    /**
     * A simple predicate or filter interface for Requests, for use by
     * {@link BallRequestQueue#cancelAll(RequestFilter)}.
     */
    public interface RequestFilter {
        public boolean apply(Request<?> request);
    }

    /**
     * Cancels all requests in this queue for which the given filter applies.
     *
     * @param filter The filtering function to use
     */
    public void cancelAll(RequestFilter filter) {
        synchronized (mCurrentRequests) {
            for (BallRequest<?> request : mCurrentRequests) {
                if (filter.apply(request)) {
                    request.cancel();
                }
            }
        }
    }

    /**
     * Cancels all requests in this queue with the given tag. Tag must be non-null
     * and equality is by identity.
     */
    public void cancelAll(final Object tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Cannot cancelAll with a null tag");
        }
        cancelAll(new RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                return request.getTag() == tag;
            }
        });
    }

    /**
     * Adds a Request to the dispatch queue.
     *
     * @param request The request to service
     * @return The passed-in request
     */
    public BallRequest add(BallRequest request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        // Process requests in the order they are added.
        request.setSequence(getSequenceNumber());
        request.addMarker("add-to-queue");

        // nothing
        if (!request.shouldProcessLocal() && !request.shouldProcessNetwork()) {
            return request;
        }

        // local only
        if (request.shouldProcessLocal() && !request.shouldProcessNetwork()) {
            mLocalQueue.add(request);
            return request;
        }

        // network

        // no cache no local
        if (!request.shouldCache() && !request.shouldProcessLocal()) {
            mNetworkQueue.add(request);
            return request;
        }

        // network with local
        if (request.shouldProcessLocal()) {
            mLocalQueue.add(request);
        }

        // network without cache, add to network directly
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
        }
        //network with cache
        else {
            // Insert request into stage if there's already a request with the same cache key in flight.
            synchronized (mWaitingRequests) {
                String cacheKey = request.getCacheKey();
                if (mWaitingRequests.containsKey(cacheKey)) {
                    // There is already a request in flight. Queue up.
                    Queue<BallRequest> stagedRequests = mWaitingRequests.get(cacheKey);
                    if (stagedRequests == null) {
                        stagedRequests = new LinkedList<BallRequest>();
                    }
                    stagedRequests.add(request);
                    mWaitingRequests.put(cacheKey, stagedRequests);
                    if (VolleyLog.DEBUG) {
                        VolleyLog.v("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
                    }
                } else {
                    // Insert 'null' queue for this cacheKey, indicating there is now a request in
                    // flight.
                    mWaitingRequests.put(cacheKey, null);
                    mCacheQueue.add(request);
                }

            }
        }

        return request;
    }

//    public Request add(Request request) {
//
//    }

    /**
     * Called from {@link com.android.volley.Request#finish(String)}, indicating that processing of the given request
     * has finished.
     * <p/>
     * <p>Releases waiting requests for <code>request.getCacheKey()</code> if
     * <code>request.shouldCache()</code>.</p>
     */
    protected void finish(Request request) {
        // Remove from the set of requests currently being processed.
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                String cacheKey = request.getCacheKey();
                Queue<BallRequest> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    if (VolleyLog.DEBUG) {
                        VolleyLog.v("Releasing %d waiting requests for cacheKey=%s.",
                                waitingRequests.size(), cacheKey);
                    }
                    // Process all queued up requests. They won't be considered as in flight, but
                    // that's not a problem as the cache has been primed by 'request'.
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }
}
