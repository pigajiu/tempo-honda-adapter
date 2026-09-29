package com.cappielloantonio.tempo.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerLyricsBinding;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Line;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.util.MusicTagBridgeClient;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.Locale;


@OptIn(markerClass = UnstableApi.class)
public class PlayerLyricsFragment extends Fragment {
    private static final String TAG = "PlayerLyricsFragment";

    private InnerFragmentPlayerLyricsBinding bind;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private MediaBrowser mediaBrowser;
    private Handler syncLyricsHandler;
    private Runnable syncLyricsRunnable;
    private int lyricsTimingOffsetMs = 0;
    private String lyricsTimingMediaId;
    private final Handler lyricsTimingRepeatHandler = new Handler(Looper.getMainLooper());
    private Runnable lyricsTimingRepeatRunnable;
    private boolean lyricsTimingRepeating;
    private boolean lyricsTimingWriteBackInProgress;

    private static final int LYRICS_TIMING_STEP_MS = 100;
    private static final long LYRICS_TIMING_LONG_PRESS_DELAY_MS = 400L;
    private static final long LYRICS_TIMING_REPEAT_INTERVAL_MS = 100L;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerLyricsBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initOverlay();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initPanelContent();
    }

    @Override
    public void onStart() {
        super.onStart();
        resetLyricsTimingOffset();
        initializeBrowser();

    }

    @Override
    public void onResume() {
        super.onResume();
        bindMediaController();
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseHandler();
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopLyricsTimingRepeat();
        super.onDestroyView();
        bind = null;
    }

    private void initOverlay() {
        bind.syncLyricsTapButton.setOnClickListener(view -> {
            playerBottomSheetViewModel.changeSyncLyricsState();
        });

        configureTimingAdjustmentButton(
                bind.lyricsTimingDelayButton,
                LYRICS_TIMING_STEP_MS
        );
        configureTimingAdjustmentButton(
                bind.lyricsTimingAdvanceButton,
                -LYRICS_TIMING_STEP_MS
        );
        bind.lyricsTimingWriteBackButton.setOnClickListener(view ->
                applyLyricsTimingOffsetToSource()
        );

        resetLyricsTimingOffset();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureTimingAdjustmentButton(View button, int deltaMs) {
        button.setOnClickListener(view -> adjustLyricsTimingOffset(deltaMs));
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    lyricsTimingRepeating = false;
                    lyricsTimingRepeatRunnable = new Runnable() {
                        @Override
                        public void run() {
                            lyricsTimingRepeating = true;
                            adjustLyricsTimingOffset(deltaMs);
                            lyricsTimingRepeatHandler.postDelayed(
                                    this,
                                    LYRICS_TIMING_REPEAT_INTERVAL_MS
                            );
                        }
                    };
                    lyricsTimingRepeatHandler.postDelayed(
                            lyricsTimingRepeatRunnable,
                            LYRICS_TIMING_LONG_PRESS_DELAY_MS
                    );
                    return true;

                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    stopLyricsTimingRepeat();
                    if (!lyricsTimingRepeating) {
                        view.performClick();
                    }
                    lyricsTimingRepeating = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    stopLyricsTimingRepeat();
                    lyricsTimingRepeating = false;
                    return true;

                default:
                    return true;
            }
        });
    }

    private void stopLyricsTimingRepeat() {
        if (lyricsTimingRepeatRunnable != null) {
            lyricsTimingRepeatHandler.removeCallbacks(lyricsTimingRepeatRunnable);
            lyricsTimingRepeatRunnable = null;
        }
    }

    private void adjustLyricsTimingOffset(int deltaMs) {
        lyricsTimingOffsetMs += deltaMs;
        updateLyricsTimingOffsetLabel();

        if (mediaBrowser != null && bind != null) {
            displaySyncedLyrics();
        }
    }

    private void resetLyricsTimingOffset() {
        lyricsTimingOffsetMs = 0;
        updateLyricsTimingOffsetLabel();
    }

    @SuppressLint("DefaultLocale")
    private void updateLyricsTimingOffsetLabel() {
        if (bind == null) return;

        String value = lyricsTimingOffsetMs == 0
                ? "0.0s"
                : String.format(Locale.US, "%+.1fs", lyricsTimingOffsetMs / 1000.0);
        bind.lyricsTimingOffsetTextView.setText(value);
        bind.lyricsTimingWriteBackButton.setEnabled(
                !lyricsTimingWriteBackInProgress
                        && lyricsTimingOffsetMs != 0
        );
    }

    private void applyLyricsTimingOffsetToSource() {
        if (lyricsTimingWriteBackInProgress || lyricsTimingOffsetMs == 0) return;

        Child media = playerBottomSheetViewModel.getLiveMedia().getValue();
        String musicPath = media != null ? media.getPath() : null;

        if (musicPath == null || musicPath.trim().isEmpty()) {
            Toast.makeText(
                    requireContext(),
                    R.string.lyrics_timing_write_back_missing_path,
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        final int appliedOffsetMs = lyricsTimingOffsetMs;
        lyricsTimingWriteBackInProgress = true;
        setTimingAdjustmentControlsEnabled(false);
        updateLyricsTimingOffsetLabel();

        MusicTagBridgeClient.applyLyricsOffset(
                musicPath,
                appliedOffsetMs,
                new MusicTagBridgeClient.Callback() {
                    @Override
                    public void onSuccess(int timestampsChanged, int timestampsClamped) {
                        if (bind == null || !isAdded()) return;

                        applyOffsetToInMemoryLyrics(appliedOffsetMs);
                        lyricsTimingOffsetMs = 0;
                        lyricsTimingWriteBackInProgress = false;
                        setTimingAdjustmentControlsEnabled(true);
                        updateLyricsTimingOffsetLabel();

                        if (mediaBrowser != null) {
                            displaySyncedLyrics();
                        }

                        Toast.makeText(
                                requireContext(),
                                R.string.lyrics_timing_write_back_success,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (bind == null || !isAdded()) return;

                        lyricsTimingWriteBackInProgress = false;
                        setTimingAdjustmentControlsEnabled(true);
                        updateLyricsTimingOffsetLabel();

                        Toast.makeText(
                                requireContext(),
                                getString(
                                        R.string.lyrics_timing_write_back_failed,
                                        message
                                ),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    private void setTimingAdjustmentControlsEnabled(boolean enabled) {
        if (bind == null) return;
        bind.lyricsTimingDelayButton.setEnabled(enabled);
        bind.lyricsTimingAdvanceButton.setEnabled(enabled);
    }

    private void applyOffsetToInMemoryLyrics(int offsetMs) {
        LyricsList lyricsList = playerBottomSheetViewModel
                .getLiveLyricsList()
                .getValue();

        if (lyricsList == null || lyricsList.getStructuredLyrics() == null) return;

        lyricsList.getStructuredLyrics().forEach(structured -> {
            if (structured == null || structured.getLine() == null) return;

            structured.getLine().forEach(line -> {
                if (line != null && line.getStart() != null) {
                    line.setStart(Math.max(0, line.getStart() + offsetMs));
                }
            });
        });
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseHandler() {
        if (syncLyricsHandler != null) {
            syncLyricsHandler.removeCallbacks(syncLyricsRunnable);
            syncLyricsHandler = null;
        }
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                mediaBrowser = mediaBrowserListenableFuture.get();
                defineProgressHandler();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void initPanelContent() {
        playerBottomSheetViewModel.getLiveMedia().observe(getViewLifecycleOwner(), media -> {
            String mediaId = media != null ? media.getId() : null;
            boolean changed = mediaId == null
                    ? lyricsTimingMediaId != null
                    : !mediaId.equals(lyricsTimingMediaId);

            if (changed || lyricsTimingMediaId == null) {
                lyricsTimingMediaId = mediaId;
                resetLyricsTimingOffset();
            }
        });

        if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> {
                setPanelContent(null, lyricsList);
            });
        } else {
            playerBottomSheetViewModel.getLiveLyrics().observe(getViewLifecycleOwner(), lyrics -> {
                setPanelContent(lyrics, null);
            });
        }
    }

    private void setPanelContent(String lyrics, LyricsList lyricsList) {
        playerBottomSheetViewModel.getLiveDescription().observe(getViewLifecycleOwner(), description -> {
            if (bind != null) {
                bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, 0);

                if (lyrics != null && !lyrics.trim().equals("")) {
                    bind.nowPlayingSongLyricsTextView.setText(MusicUtil.getReadableLyrics(lyrics));
                    bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                    bind.lyricsTimingAdjustmentPanel.setVisibility(View.GONE);
                } else if (lyricsList != null && lyricsList.getStructuredLyrics() != null) {
                    setSyncLirics(lyricsList);
                    bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);

                    boolean hasSyncedLyrics = !lyricsList.getStructuredLyrics().isEmpty()
                            && lyricsList.getStructuredLyrics().get(0) != null
                            && lyricsList.getStructuredLyrics().get(0).getSynced();
                    bind.syncLyricsTapButton.setVisibility(hasSyncedLyrics ? View.VISIBLE : View.GONE);
                    bind.lyricsTimingAdjustmentPanel.setVisibility(hasSyncedLyrics ? View.VISIBLE : View.GONE);
                } else if (description != null && !description.trim().equals("")) {
                    bind.nowPlayingSongLyricsTextView.setText(MusicUtil.getReadableLyrics(description));
                    bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
                    bind.emptyDescriptionImageView.setVisibility(View.GONE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                    bind.lyricsTimingAdjustmentPanel.setVisibility(View.GONE);
                } else {
                    bind.nowPlayingSongLyricsTextView.setVisibility(View.GONE);
                    bind.emptyDescriptionImageView.setVisibility(View.VISIBLE);
                    bind.titleEmptyDescriptionLabel.setVisibility(View.VISIBLE);
                    bind.syncLyricsTapButton.setVisibility(View.GONE);
                    bind.lyricsTimingAdjustmentPanel.setVisibility(View.GONE);
                }
            }
        });
    }

    @SuppressLint("DefaultLocale")
    private void setSyncLirics(LyricsList lyricsList) {
        if (lyricsList.getStructuredLyrics() != null && !lyricsList.getStructuredLyrics().isEmpty() && lyricsList.getStructuredLyrics().get(0).getLine() != null) {
            StringBuilder lyricsBuilder = new StringBuilder();
            List<Line> lines = lyricsList.getStructuredLyrics().get(0).getLine();

            if (lines != null) {
                for (Line line : lines) {
                    lyricsBuilder.append(line.getValue().trim()).append("\n");
                }
            }

            bind.nowPlayingSongLyricsTextView.setText(lyricsBuilder.toString());
        }
    }

    private void defineProgressHandler() {
        playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> {
            if (lyricsList != null) {

                if (lyricsList.getStructuredLyrics() != null && lyricsList.getStructuredLyrics().get(0) != null && !lyricsList.getStructuredLyrics().get(0).getSynced()) {
                    releaseHandler();
                    return;
                }

                syncLyricsHandler = new Handler();
                syncLyricsRunnable = () -> {
                    if (syncLyricsHandler != null) {
                        if (bind != null) {
                            displaySyncedLyrics();
                        }

                        syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
                    }
                };

                syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
            } else {
                releaseHandler();
            }
        });
    }

    private void displaySyncedLyrics() {
        LyricsList lyricsList = playerBottomSheetViewModel.getLiveLyricsList().getValue();
        long adjustedTimestamp = mediaBrowser.getCurrentPosition() - lyricsTimingOffsetMs;
        int timestamp = (int) Math.max(0L, adjustedTimestamp);

        if (lyricsList != null && lyricsList.getStructuredLyrics() != null && !lyricsList.getStructuredLyrics().isEmpty() && lyricsList.getStructuredLyrics().get(0).getLine() != null) {
            StringBuilder lyricsBuilder = new StringBuilder();
            List<Line> lines = lyricsList.getStructuredLyrics().get(0).getLine();

            if (lines == null || lines.isEmpty()) return;

            for (Line line : lines) {
                lyricsBuilder.append(line.getValue().trim()).append("\n");
            }

            Line toHighlight = lines.stream().filter(line -> line != null && line.getStart() != null && line.getStart() < timestamp).reduce((first, second) -> second).orElse(null);

            if (toHighlight != null) {
                String lyrics = lyricsBuilder.toString();
                Spannable spannableString = new SpannableString(lyrics);

                int startingPosition = getStartPosition(lines, toHighlight);
                int endingPosition = startingPosition + toHighlight.getValue().length();

                spannableString.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.shadowsLyricsTextColor, null)), 0, lyrics.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannableString.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.lyricsTextColor, null)), startingPosition, endingPosition, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                bind.nowPlayingSongLyricsTextView.setText(spannableString);

                if (playerBottomSheetViewModel.getSyncLyricsState()) {
                    bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, getScroll(lines, toHighlight));
                }
            }
        }
    }

    private int getStartPosition(List<Line> lines, Line toHighlight) {
        int start = 0;

        for (Line line : lines) {
            if (line != toHighlight) {
                start = start + line.getValue().length() + 1;
            } else {
                break;
            }
        }

        return start;
    }

    private int getLineCount(List<Line> lines, Line toHighlight) {
        int start = 0;

        for (Line line : lines) {
            if (line != toHighlight) {
                bind.tempLyricsLineTextView.setText(line.getValue());
                start = start + bind.tempLyricsLineTextView.getLineCount();
            } else {
                break;
            }
        }

        return start;
    }

    private int getScroll(List<Line> lines, Line toHighlight) {
        int lineHeight = bind.nowPlayingSongLyricsTextView.getLineHeight();
        int lineCount = getLineCount(lines, toHighlight);
        int scrollViewHeight = bind.nowPlayingSongLyricsSrollView.getHeight();

        return lineHeight * lineCount < scrollViewHeight / 2 ? 0 : lineHeight * lineCount - scrollViewHeight / 2 + lineHeight;
    }
}