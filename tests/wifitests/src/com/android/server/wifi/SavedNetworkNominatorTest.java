/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;

import static org.mockito.Mockito.*;

import android.net.wifi.WifiConfiguration;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LocalLog;

import com.android.server.wifi.WifiNetworkSelector.NetworkNominator.OnConnectableListener;
import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;
import com.android.server.wifi.util.TelephonyUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link SavedNetworkNominator}.
 */
@SmallTest
public class SavedNetworkNominatorTest extends WifiBaseTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLocalLog = new LocalLog(512);

        mSavedNetworkEvaluator = new SavedNetworkNominator(mWifiConfigManager, mLocalLog,
                mTelephonyUtil);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private ArgumentCaptor<WifiConfiguration> mWifiConfigurationArgumentCaptor =
            ArgumentCaptor.forClass(WifiConfiguration.class);
    private static final int INVALID_SUBID = 1;
    private static final int TEST_CARRIER_ID = 100;
    private static final int RSSI_LEVEL = -50;

    private SavedNetworkNominator mSavedNetworkEvaluator;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private Clock mClock;
    @Mock private OnConnectableListener mOnConnectableListener;
    @Mock private TelephonyUtil mTelephonyUtil;
    private LocalLog mLocalLog;

    /**
     * Do not evaluate networks that {@link WifiConfiguration#useExternalScores}.
     */
    @Test
    public void ignoreNetworksIfUseExternalScores() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-PSK][ESS]", "[WPA2-PSK][ESS]"};
        int[] levels = {RSSI_LEVEL, RSSI_LEVEL};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        for (WifiConfiguration wifiConfiguration : savedConfigs) {
            wifiConfiguration.useExternalScores = true;
        }

        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);

        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }

    /**
     * Do not evaluate networks which require SIM card when the SIM card is absent.
     */
    @Test
    public void ignoreNetworkIfSimIsAbsentForEapSimNetwork() {
        String[] ssids = {"\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3"};
        int[] freqs = {2470};
        int[] levels = {RSSI_LEVEL};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigForEapSimNetwork(ssids, bssids,
                        freqs, levels, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        savedConfigs[0].carrierId = TEST_CARRIER_ID;
        // SIM is absent
        when(mTelephonyUtil.getBestMatchSubscriptionId(any(WifiConfiguration.class)))
                .thenReturn(INVALID_SUBID);
        when(mTelephonyUtil.isSimPresent(eq(INVALID_SUBID))).thenReturn(false);

        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);

        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }

    /**
     * Do not evaluate networks that {@link WifiConfiguration#isEphemeral}.
     */
    @Test
    public void ignoreEphemeralNetworks() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {RSSI_LEVEL, RSSI_LEVEL};
        int[] securities = {SECURITY_NONE, SECURITY_NONE};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        for (WifiConfiguration wifiConfiguration : savedConfigs) {
            wifiConfiguration.ephemeral = true;
        }

        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);

        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }

    /**
     * Pick a worse candidate that allows auto-join over a better candidate that
     * disallows auto-join.
     */
    @Test
    public void ignoreNetworksIfAutojoinNotAllowed() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {RSSI_LEVEL, RSSI_LEVEL};
        int[] securities = {SECURITY_NONE, SECURITY_NONE};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);

        verify(mOnConnectableListener, times(2)).onConnectable(any(), any());
        reset(mOnConnectableListener);
        savedConfigs[1].allowAutojoin = false;
        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);
        verify(mOnConnectableListener).onConnectable(any(),
                mWifiConfigurationArgumentCaptor.capture());
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0],
                mWifiConfigurationArgumentCaptor.getValue());
    }

    /**
     * Do not return a candidate if all networks do not {@link WifiConfiguration#allowAutojoin}
     */
    @Test
    public void returnNoCandidateIfNoNetworksAllowAutojoin() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {RSSI_LEVEL, RSSI_LEVEL};
        int[] securities = {SECURITY_NONE, SECURITY_NONE};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        for (WifiConfiguration wifiConfiguration : savedConfigs) {
            wifiConfiguration.allowAutojoin = false;
        }

        mSavedNetworkEvaluator.nominateNetworks(scanDetails,
                null, null, true, false, mOnConnectableListener);

        verify(mOnConnectableListener, never()).onConnectable(any(), any());
    }
}