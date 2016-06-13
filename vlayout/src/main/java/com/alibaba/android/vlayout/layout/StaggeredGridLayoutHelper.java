package com.alibaba.android.vlayout.layout;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.OrientationHelper;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.LayoutParams;
import android.view.View;

import com.alibaba.android.vlayout.LayoutManagerHelper;
import com.alibaba.android.vlayout.Range;
import com.alibaba.android.vlayout.VirtualLayoutManager;
import com.alibaba.android.vlayout.VirtualLayoutManager.LayoutStateWrapper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

import static android.support.v7.widget.LinearLayoutManager.INVALID_OFFSET;
import static com.alibaba.android.vlayout.VirtualLayoutManager.HORIZONTAL;
import static com.alibaba.android.vlayout.VirtualLayoutManager.LayoutStateWrapper.LAYOUT_END;
import static com.alibaba.android.vlayout.VirtualLayoutManager.LayoutStateWrapper.LAYOUT_START;
import static com.alibaba.android.vlayout.VirtualLayoutManager.VERTICAL;

/**
 * @author villadora
 * @since 1.1.0
 */
public class StaggeredGridLayoutHelper extends BaseLayoutHelper {

    private static final String LOOKUP_BUNDLE_KEY = StaggeredGridLayoutHelper.class.getSimpleName() + "_LazySpanLookup";

    private static final int INVALID_SPAN_ID = Integer.MIN_VALUE;
    static final int INVALID_LINE = Integer.MIN_VALUE;

    private int mNumLanes = 0;

    private Span[] mSpans;

    private int mHGap = 0;

    private int mVGap = 0;


    // length specs
    private int mColLength = 0;
    private int mEachGap = 0;
    private int mLastGap = 0;


    private BitSet mRemainingSpans = null;

    private LazySpanLookup mLazySpanLookup = new LazySpanLookup();


    private WeakReference<VirtualLayoutManager> mLayoutManager = null;

    private final Runnable checkForGapsRunnable = new Runnable() {
        @Override
        public void run() {
            checkForGaps();
        }
    };

    public StaggeredGridLayoutHelper() {
        this(1, 0);
    }

    public StaggeredGridLayoutHelper(int lanes) {
        this(lanes, 0);
    }

    public StaggeredGridLayoutHelper(int lanes, int gap) {
        setLane(lanes);
        setGap(gap);
    }

    public void setGap(int gap) {
        setHGap(gap);
        setVGap(gap);
    }

    public void setHGap(int hGap) {
        this.mHGap = hGap;
    }

    public void setVGap(int vGap) {
        this.mVGap = vGap;
    }

    public void setLane(int lane) {
        this.mNumLanes = lane;
        ensureLanes();
    }


    private void ensureLanes() {
        if (mSpans == null || mSpans.length != mNumLanes || mRemainingSpans == null) {
            mRemainingSpans = new BitSet(mNumLanes);
            mSpans = new Span[mNumLanes];
            for (int i = 0; i < mNumLanes; i++) {
                mSpans[i] = new Span(i);
            }
        }
    }

    @Override
    public void beforeLayout(RecyclerView.Recycler recycler, RecyclerView.State state, LayoutManagerHelper helper) {
        super.beforeLayout(recycler, state, helper);

        int availableWidth;
        if (helper.getOrientation() == VERTICAL) {
            availableWidth = helper.getContentWidth() - helper.getPaddingLeft() - helper.getPaddingRight() - mMarginLeft - mMarginRight;
        } else {
            availableWidth = helper.getContentHeight() - helper.getPaddingTop() - helper.getPaddingBottom() - mMarginTop - mMarginBottom;
        }
        mColLength = (int) ((availableWidth - mHGap * (mNumLanes - 1)) / mNumLanes + 0.5);
        int totalGaps = availableWidth - mColLength * mNumLanes;

        if (mNumLanes <= 1) {
            mEachGap = mLastGap = 0;
        } else if (mNumLanes == 2) {
            mEachGap = totalGaps;
            mLastGap = totalGaps;
        } else {
            mEachGap = (int) (totalGaps / (mNumLanes - 1) + 0.5);
            mLastGap = totalGaps - (mNumLanes - 2) * mEachGap;
        }

        if (mLayoutManager == null || mLayoutManager.get() == null || mLayoutManager.get() != helper) {
            if (helper instanceof VirtualLayoutManager) {
                mLayoutManager = new WeakReference<VirtualLayoutManager>((VirtualLayoutManager) helper);
            }
        }
    }

    @Override
    public void afterLayout(RecyclerView.Recycler recycler, RecyclerView.State state, int startPosition, int endPosition, int scrolled, LayoutManagerHelper helper) {
        super.afterLayout(recycler, state, startPosition, endPosition, scrolled, helper);

        if (startPosition > getRange().getUpper() || endPosition < getRange().getLower()) return;

        if (!state.isPreLayout() && helper.getChildCount() > 0) {
            // call after doing layout, to check whether there is a gap between staggered layout and other layouts
            ViewCompat.postOnAnimation(helper.getChildAt(0), checkForGapsRunnable);
        }
    }


    @Override
    public void layoutViews(RecyclerView.Recycler recycler, RecyclerView.State state,
                            LayoutStateWrapper layoutState, LayoutChunkResult result,
                            LayoutManagerHelper helper) {
        if (isOutOfRange(layoutState.getCurrentPosition())) {
            return;
        }

        ensureLanes();

        final boolean layoutInVertical = helper.getOrientation() == VERTICAL;
        final OrientationHelper orientationHelper = helper.getMainOrientationHelper();
        final OrientationHelper secondaryOrientationHelper = helper.getSecondaryOrientationHelper();


        mRemainingSpans.set(0, mNumLanes, true);

        final int targetLine;
        final int recycleLine;

        // Line of the furthest row.
        if (layoutState.getLayoutDirection() == LAYOUT_END) {
            // ignore padding for recycler
            recycleLine = layoutState.getOffset() + layoutState.getAvailable();
            targetLine = recycleLine + layoutState.getExtra() + orientationHelper.getEndPadding();
        } else { // LAYOUT_START
            // ignore padding for recycler
            recycleLine = layoutState.getOffset() - layoutState.getAvailable();
            targetLine = recycleLine - layoutState.getExtra() - orientationHelper.getStartAfterPadding();
        }

        updateAllRemainingSpans(layoutState.getLayoutDirection(), targetLine, orientationHelper);

        final int defaultNewViewLine = layoutState.getOffset();

        while (layoutState.hasMore(state) && !mRemainingSpans.isEmpty() && !isOutOfRange(layoutState.getCurrentPosition())) {
            boolean isStartLine = false, isEndLine = false;
            View view = layoutState.next(recycler);

            if (view == null)
                break;

            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();

            // find the span to put the view
            final int position = lp.getViewPosition();
            final int spanIndex = mLazySpanLookup.getSpan(position);
            Span currentSpan;
            boolean assignSpan = spanIndex == INVALID_SPAN_ID;
            if (assignSpan) {
                currentSpan = getNextSpan(defaultNewViewLine, layoutState, helper);
                mLazySpanLookup.setSpan(position, currentSpan);
            } else {
                currentSpan = mSpans[spanIndex];
            }


            // handle margin for start/end line
            isStartLine = position - getRange().getLower() < mNumLanes;
            isEndLine = getRange().getUpper() - position - 1 < mNumLanes;

            helper.addChildView(layoutState, view);

            if (layoutInVertical) {
                int widthSpec = helper.getChildMeasureSpec(mColLength, lp.width, false);
                int heightSpec = helper.getChildMeasureSpec(orientationHelper.getTotalSpace(), lp.height, true);
                helper.measureChild(view, widthSpec, heightSpec);
            } else {
                int heightSpec = helper.getChildMeasureSpec(mColLength, lp.height, false);
                int widthSpec = helper.getChildMeasureSpec(orientationHelper.getTotalSpace(), lp.width, true);
                helper.measureChild(view, widthSpec, heightSpec);
            }


            int start;
            int end;

            if (layoutState.getLayoutDirection() == LAYOUT_END) {
                start = currentSpan.getEndLine(defaultNewViewLine, orientationHelper);

                if (isStartLine)
                    start += layoutInVertical ? mMarginTop : mMarginLeft;
                else
                    start += (layoutInVertical ? mVGap : mHGap);
                end = start + orientationHelper.getDecoratedMeasurement(view);
            } else {
                if (isEndLine)
                    end = currentSpan.getStartLine(defaultNewViewLine, orientationHelper) - (layoutInVertical ? mMarginBottom : mMarginRight);
                else
                    end = currentSpan.getStartLine(defaultNewViewLine, orientationHelper) - (layoutInVertical ? mVGap : mHGap);
                start = end - orientationHelper.getDecoratedMeasurement(view);
            }

            // lp.mSpan = currentSpan;
            if (layoutState.getLayoutDirection() == LAYOUT_END) {
                currentSpan.appendToSpan(view, orientationHelper);
            } else {
                currentSpan.prependToSpan(view, orientationHelper);
            }

            // left, right in vertical layout
            int otherStart =
                    ((currentSpan.mIndex == mNumLanes - 1) ?
                            currentSpan.mIndex * (mColLength + mEachGap) - mEachGap + mLastGap
                            : currentSpan.mIndex * (mColLength + mEachGap)) +
                            secondaryOrientationHelper.getStartAfterPadding();

            if (currentSpan.mIndex == 0) {
                if (layoutInVertical) {
                    otherStart += mMarginLeft;
                } else {
                    otherStart += mMarginTop;
                }
            }

            int otherEnd = otherStart + orientationHelper.getDecoratedMeasurementInOther(view);

            if (currentSpan.mIndex == mNumLanes - 1) {
                if (layoutInVertical) {
                    otherStart += mMarginRight;
                    otherEnd += mMarginRight;
                } else {
                    otherStart += mMarginBottom;
                    otherEnd += mMarginBottom;
                }
            }

            if (layoutInVertical) {
                layoutChild(view, otherStart, start, otherEnd, end, helper);
            } else {
                layoutChild(view, start, otherStart, end, otherEnd, helper);
            }

            updateRemainingSpans(currentSpan, layoutState.getLayoutDirection(), targetLine, orientationHelper);

            recycle(recycler, layoutState, currentSpan, recycleLine, helper);

            handleStateOnResult(result, view);
        }

        if (isOutOfRange(layoutState.getCurrentPosition())) {
            // reach the end of layout, cache the gap
            // TODO: how to retain gap
            if (layoutState.getLayoutDirection() == LayoutStateWrapper.LAYOUT_START) {
                for (Span span : mSpans) {
                    if (span.mCachedStart != INVALID_LINE) {
                        span.mLastEdgeStart = span.mCachedStart;
                    }
                }
            } else {
                for (Span span : mSpans) {
                    if (span.mCachedEnd != INVALID_LINE) {
                        span.mLastEdgeEnd = span.mCachedEnd;
                    }
                }
            }
        }

        if (layoutState.getLayoutDirection() == LayoutStateWrapper.LAYOUT_START) {
            if (!isOutOfRange(layoutState.getCurrentPosition()) && layoutState.hasMore(state)) {
                final int maxStart = getMaxStart(orientationHelper.getStartAfterPadding(), orientationHelper);
                result.mConsumed = layoutState.getOffset() - maxStart;
            } else {
                final int minStart = getMinStart(orientationHelper.getEndAfterPadding(), orientationHelper);
                result.mConsumed = layoutState.getOffset() - minStart + (layoutInVertical ? mMarginTop : mMarginLeft);
            }
        } else {
            if (!isOutOfRange(layoutState.getCurrentPosition()) && layoutState.hasMore(state)) {
                final int minEnd = getMinEnd(orientationHelper.getEndAfterPadding(), orientationHelper);
                result.mConsumed = minEnd - layoutState.getOffset();
            } else {
                final int maxEnd = getMaxEnd(orientationHelper.getEndAfterPadding(), orientationHelper);
                result.mConsumed = maxEnd - layoutState.getOffset() + (layoutInVertical ? mMarginBottom : mMarginRight);
            }

        }
    }


    @Override
    public void onScrollStateChanged(int state, int startPosition,
                                     int endPosition, LayoutManagerHelper helper) {
        if (startPosition > getRange().getUpper() || endPosition < getRange().getLower())
            return;

        if (state == RecyclerView.SCROLL_STATE_IDLE) {
            checkForGaps();
        }
    }


    @Override
    public int computeAlignOffset(int offset, boolean isLayoutEnd,
                                  boolean useAnchor, LayoutManagerHelper helper) {
        final boolean layoutInVertical = helper.getOrientation() == VERTICAL;
        final OrientationHelper orientationHelper = helper.getMainOrientationHelper();
        final View child = helper.findViewByPosition(offset + getRange().getLower());

        if (child == null) {
            return 0;
        }

        if (layoutInVertical) {
            // in middle nothing need to do
            if (isLayoutEnd) {
                if (offset == getItemCount() - 1) {
                    return mMarginBottom;
                } else if (!useAnchor) {
                    final int minEnd = getMinEnd(orientationHelper.getDecoratedStart(child), orientationHelper);
                    return minEnd - orientationHelper.getDecoratedEnd(child);
                }
            } else {
                if (offset == 0) {
                    return -mMarginTop;
                } else if (!useAnchor) {
                    final int maxStart = getMaxStart(orientationHelper.getDecoratedEnd(child), orientationHelper);
                    return maxStart - orientationHelper.getDecoratedStart(child);
                }
            }

        } else {

        }

        return 0;
    }


    @Override
    public void onClear(LayoutManagerHelper helper) {
        super.onClear(helper);
        mLazySpanLookup.clear();
        mSpans = null;
        mLayoutManager = null;
    }

    /**
     * check whether there are gaps that need to be fixed
     */

    private void checkForGaps() {
        if (mLayoutManager == null) return;

        final VirtualLayoutManager layoutManager = mLayoutManager.get();

        if (layoutManager == null || layoutManager.getChildCount() == 0) {
            return;
        }

        final Range<Integer> range = getRange();

        // align position, which should check gap for
        final int minPos, maxPos, alignPos;
        if (layoutManager.getReverseLayout()) {
            minPos = layoutManager.findLastVisibleItemPosition();
            maxPos = layoutManager.findFirstVisibleItemPosition();
            alignPos = range.getUpper() - 1;
        } else {
            minPos = layoutManager.findFirstVisibleItemPosition();
            maxPos = layoutManager.findLastCompletelyVisibleItemPosition();
            alignPos = range.getLower();
        }


        final OrientationHelper orientationHelper = layoutManager.getMainOrientationHelper();
        final int childCount = layoutManager.getChildCount();
        int viewAnchor = Integer.MIN_VALUE;
        int alignLine = Integer.MIN_VALUE;

        // find view anchor and get align line, the views should be aligned to alignLine
        if (layoutManager.getReverseLayout()) {
            for (int i = childCount - 1; i >= 0; i--) {
                View view = layoutManager.getChildAt(i);
                int position = layoutManager.getPosition(view);
                if (position == alignPos) {
                    viewAnchor = position;
                    if (i == childCount - 1) {
                        // if last child, alignLine is the end of child
                        alignLine = orientationHelper.getDecoratedEnd(view);
                    } else {
                        // if not, alignLine is the start of next child
                        View child = layoutManager.getChildAt(i + 1);
                        int aPos = layoutManager.getPosition(child);
                        if (aPos == position - 1) {
                            // if position is sequence, which means the next child is not hidden one
                            alignLine = orientationHelper.getDecoratedStart(child) - layoutManager.obtainExtraMargin(child, false)
                                    + layoutManager.obtainExtraMargin(view, true);
                        } else {
                            // if next child is hidden one, use end of current view
                            alignLine = orientationHelper.getDecoratedEnd(view);
                        }
                    }
                    break;
                }
            }
        } else {
            for (int i = 0; i < childCount; i++) {
                View view = layoutManager.getChildAt(i);
                int position = layoutManager.getPosition(view);
                if (position == alignPos) {
                    viewAnchor = position;
                    if (i == 0) {
                        // TODO: there is problem
                        // if first child, alignLine is the start
                        alignLine = orientationHelper.getDecoratedStart(view);
                    } else {
                        // if not, alignLine is the end of previous child
                        View child = layoutManager.getChildAt(i - 1);
                        alignLine = orientationHelper.getDecoratedEnd(child) + layoutManager.obtainExtraMargin(child, true)
                                - layoutManager.obtainExtraMargin(view, false);
                    }
                    break;
                }
            }
        }

        if (viewAnchor == Integer.MIN_VALUE) {
            // if not find view anchor, break
            return;
        }

        View gapView = hasGapsToFix(layoutManager, viewAnchor, alignLine);
        if (gapView != null) {
            mLazySpanLookup.clear();

            for (Span span : mSpans) {
                span.setLine(alignLine);
            }

            layoutManager.requestSimpleAnimationsInNextLayout();
            layoutManager.requestLayout();
        }
    }

    /**
     * Checks for gaps if we've reached to the top of the list.
     * <p/>
     * Intermediate gaps created by full span items are tracked via mLaidOutInvalidFullSpan field.
     */
    private View hasGapsToFix(VirtualLayoutManager layoutManager, final int position, final int alignLine) {
        View view = layoutManager.findViewByPosition(position);

        if (view == null) return null;


        BitSet mSpansToCheck = new BitSet(mNumLanes);
        mSpansToCheck.set(0, mNumLanes, true);

        for (Span span : mSpans) {
            if (span.mViews.size() != 0 && checkSpanForGap(span, layoutManager, alignLine)) {
                return layoutManager.getReverseLayout() ? span.mViews.get(span.mViews.size() - 1) : span.mViews.get(0);
            }
        }

        // everything looks good
        return null;
    }


    private boolean checkSpanForGap(Span span, VirtualLayoutManager layoutManager, int line) {
        OrientationHelper orientationHelper = layoutManager.getMainOrientationHelper();
        if (layoutManager.getReverseLayout()) {
            if (span.getEndLine(orientationHelper) < line) {
                return true;
            }
        } else if (span.getStartLine(orientationHelper) > line) {
            return true;
        }
        return false;
    }


    private void recycle(RecyclerView.Recycler recycler, LayoutStateWrapper layoutState,
                         Span updatedSpan, int recycleLine, LayoutManagerHelper helper) {
        OrientationHelper orientation = helper.getMainOrientationHelper();
        if (layoutState.getLayoutDirection() == LAYOUT_START) {
            // calculate recycle line
            int maxStart = getMaxStart(updatedSpan.getStartLine(orientation), orientation);
            recycleFromEnd(recycler, Math.max(recycleLine, maxStart) +
                    (orientation.getEnd() - orientation.getStartAfterPadding()), helper);
        } else {
            // calculate recycle line
            int minEnd = getMinEnd(updatedSpan.getEndLine(orientation), orientation);
            recycleFromStart(recycler, Math.min(recycleLine, minEnd) -
                    (orientation.getEnd() - orientation.getStartAfterPadding()), helper);
        }
    }

    private void recycleFromStart(RecyclerView.Recycler recycler, int line, LayoutManagerHelper helper) {
        final OrientationHelper orientationHelper = helper.getMainOrientationHelper();
        boolean changed = true;
        while (helper.getChildCount() > 0 && changed) {
            View child = helper.getChildAt(0);
            if (child != null && orientationHelper.getDecoratedEnd(child) < line) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int position = lp.getViewPosition();
                Span span = findSpan(position, child, true);
                if (span != null) {
                    span.popStart(orientationHelper);
                    helper.removeChildView(child);
                    recycler.recycleView(child);
                } else {
                    changed = false;
                }
            } else {
                return;// done
            }
        }
    }

    private void recycleFromEnd(RecyclerView.Recycler recycler, int line, LayoutManagerHelper helper) {
        final OrientationHelper orientationHelper = helper.getMainOrientationHelper();
        final int childCount = helper.getChildCount();
        int i;
        for (i = childCount - 1; i >= 0; i--) {
            View child = helper.getChildAt(i);
            if (child != null && orientationHelper.getDecoratedStart(child) > line) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int position = lp.getViewPosition();
                Span span = findSpan(position, child, false);
                if (span != null) {
                    span.popEnd(orientationHelper);
                    helper.removeChildView(child);
                    recycler.recycleView(child);
                }
            } else {
                return;// done
            }
        }
    }


    private Span findSpan(int position, View child, boolean isStart) {
        int span = mLazySpanLookup.getSpan(position);
        if (span >= 0 && span < mSpans.length) {
            Span sp = mSpans[span];
            if (isStart && sp.findStart(child)) {
                return sp;
            } else if (!isStart && sp.findEnd(child)) {
                return sp;
            }
        }

        for (int i = 0; i < mSpans.length; i++) {
            if (i == span) continue;

            Span sp = mSpans[i];
            if (isStart && sp.findStart(child)) {
                return sp;
            } else if (!isStart && sp.findEnd(child)) {
                return sp;
            }
        }

        return null;
    }

    @Override
    public boolean isRecyclable(int childPos, int startIndex, int endIndex, LayoutManagerHelper helper, boolean fromStart) {
        // startIndex == endIndex already be ignored in VirtualLayoutManager.recycleChildren
        final boolean recyclable = super.isRecyclable(childPos, startIndex, endIndex, helper, fromStart);

        if (recyclable) {
            View child = helper.findViewByPosition(childPos);

            if (child != null) {
                final OrientationHelper orientationHelper = helper.getMainOrientationHelper();
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                int position = lp.getViewPosition();

                if (helper.getReverseLayout()) {
                    if (fromStart) { // recycle from end
                        Span span = findSpan(position, child, true);
                        if (span != null) {
                            span.popEnd(orientationHelper);
                        }
                    } else { // recycle from start
                        Span span = findSpan(position, child, false);
                        if (span != null) {
                            span.popStart(orientationHelper);
                        }
                    }
                } else {
                    if (fromStart) { // recycle from start
                        Span span = findSpan(position, child, true);
                        if (span != null) {
                            span.popStart(orientationHelper);
                        }
                    } else { // recycle from end
                        Span span = findSpan(position, child, false);
                        if (span != null) {
                            span.popEnd(orientationHelper);
                        }
                    }
                }
            }
        }

        return recyclable;
    }

    private void updateAllRemainingSpans(int layoutDir, int targetLine, OrientationHelper helper) {
        for (int i = 0; i < mNumLanes; i++) {
            if (mSpans[i].mViews.isEmpty()) {
                continue;
            }
            updateRemainingSpans(mSpans[i], layoutDir, targetLine, helper);
        }
    }

    private void updateRemainingSpans(Span span, int layoutDir, int targetLine, OrientationHelper helper) {
        final int deletedSize = span.getDeletedSize();
        if (layoutDir == LayoutStateWrapper.LAYOUT_START) {
            final int line = span.getStartLine(helper);
            if (line + deletedSize < targetLine) {
                mRemainingSpans.set(span.mIndex, false);
            }
        } else {
            final int line = span.getEndLine(helper);
            if (line - deletedSize > targetLine) {
                mRemainingSpans.set(span.mIndex, false);
            }
        }
    }

    /**
     * Finds the span for the next view.
     */
    private Span getNextSpan(int defaultLine, LayoutStateWrapper layoutState, LayoutManagerHelper helper) {
        OrientationHelper orientationHelper = helper.getMainOrientationHelper();
        boolean preferLastSpan = false;

        if (helper.getOrientation() == HORIZONTAL) {
            preferLastSpan = (layoutState.getLayoutDirection() == LAYOUT_START) != helper.getReverseLayout();
        } else {
            preferLastSpan = ((layoutState.getLayoutDirection() == LAYOUT_START) == helper.getReverseLayout()) == helper.isDoLayoutRTL();
        }


        final int startIndex, endIndex, diff;
        if (preferLastSpan) {
            startIndex = mNumLanes - 1;
            endIndex = -1;
            diff = -1;
        } else {
            startIndex = 0;
            endIndex = mNumLanes;
            diff = 1;
        }
        if (layoutState.getLayoutDirection() == LAYOUT_END) {
            Span min = null;
            int minLine = Integer.MAX_VALUE;
            for (int i = startIndex; i != endIndex; i += diff) {
                final Span other = mSpans[i];
                int otherLine = other.getEndLine(defaultLine, orientationHelper);
                if (otherLine < minLine) {
                    min = other;
                    minLine = otherLine;
                }
            }
            return min;
        } else {
            Span max = null;
            int maxLine = Integer.MIN_VALUE;
            for (int i = startIndex; i != endIndex; i += diff) {
                final Span other = mSpans[i];
                int otherLine = other.getStartLine(defaultLine, orientationHelper);
                if (otherLine > maxLine) {
                    max = other;
                    maxLine = otherLine;
                }
            }
            return max;
        }
    }


    private int getMaxStart(int def, OrientationHelper helper) {
        int maxStart = mSpans[0].getStartLine(def, helper);
        for (int i = 1; i < mNumLanes; i++) {
            final int spanStart = mSpans[i].getStartLine(def, helper);
            if (spanStart > maxStart) {
                maxStart = spanStart;
            }
        }
        return maxStart;
    }

    private int getMinStart(int def, OrientationHelper helper) {
        int minStart = mSpans[0].getStartLine(def, helper);
        for (int i = 1; i < mNumLanes; i++) {
            final int spanStart = mSpans[i].getStartLine(def, helper);
            if (spanStart < minStart) {
                minStart = spanStart;
            }
        }
        return minStart;
    }

    private int getMaxEnd(int def, OrientationHelper helper) {
        int maxEnd = mSpans[0].getEndLine(def, helper);
        for (int i = 1; i < mNumLanes; i++) {
            final int spanEnd = mSpans[i].getEndLine(def, helper);
            if (spanEnd > maxEnd) {
                maxEnd = spanEnd;
            }
        }
        return maxEnd;
    }

    private int getMinEnd(int def, OrientationHelper helper) {
        int minEnd = mSpans[0].getEndLine(def, helper);
        for (int i = 1; i < mNumLanes; i++) {
            final int spanEnd = mSpans[i].getEndLine(def, helper);
            if (spanEnd < minEnd) {
                minEnd = spanEnd;
            }
        }
        return minEnd;
    }


    @Override
    public void onRefreshLayout(RecyclerView.State state, VirtualLayoutManager.AnchorInfoWrapper anchorInfo, LayoutManagerHelper helper) {
        super.onRefreshLayout(state, anchorInfo, helper);
        ensureLanes();

        if (isOutOfRange(anchorInfo.position)) {
            for (Span span : mSpans) {
                span.clear();
            }
        }
    }

    @Override
    public void checkAnchorInfo(RecyclerView.State state, VirtualLayoutManager.AnchorInfoWrapper anchorInfo, LayoutManagerHelper helper) {
        super.checkAnchorInfo(state, anchorInfo, helper);
        ensureLanes();

        final Range<Integer> range = getRange();
        if (anchorInfo.layoutFromEnd) {
            if (anchorInfo.position < range.getLower() + mNumLanes - 1) {
                anchorInfo.position = Math.min(range.getLower() + mNumLanes - 1, range.getUpper());
            }
        } else {
            if (anchorInfo.position > range.getUpper() - (mNumLanes - 1)) {
                anchorInfo.position = Math.max(range.getLower(), range.getUpper() - (mNumLanes - 1));
            }
        }


        View reference = helper.findViewByPosition(anchorInfo.position);

        final boolean layoutInVertical = helper.getOrientation() == VERTICAL;
        int mainGap = layoutInVertical ? mVGap : mHGap;
        final OrientationHelper orientationHelper = helper.getMainOrientationHelper();

        if (reference == null) {
            for (Span span : mSpans) {
                span.clear();
                span.setLine(anchorInfo.coordinate);
            }
        } else {
            int anchorPos = anchorInfo.layoutFromEnd ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            for (Span span : mSpans) {
                if (!span.mViews.isEmpty()) {
                    if (anchorInfo.layoutFromEnd) {
                        View view = span.mViews.get(span.mViews.size() - 1);
                        anchorPos = Math.max(anchorPos, helper.getPosition(view));
                    } else {
                        View view = span.mViews.get(0);
                        anchorPos = Math.min(anchorPos, helper.getPosition(view));
                    }
                }
            }

            int offset = INVALID_OFFSET;
            if (!isOutOfRange(anchorPos)) {
                boolean isStartLine = anchorPos == range.getLower();
                View view = helper.findViewByPosition(anchorPos);

                if (view != null) {
                    if (anchorInfo.layoutFromEnd) {
                        anchorInfo.position = anchorPos;
                        final int endRef = orientationHelper.getDecoratedEnd(reference);
                        if (endRef < anchorInfo.coordinate) {
                            offset = anchorInfo.coordinate - endRef;
                            offset += (isStartLine ? 0 : mainGap);
                            anchorInfo.coordinate = orientationHelper.getDecoratedEnd(view) + offset;
                        } else {
                            offset = (isStartLine ? 0 : mainGap);
                            anchorInfo.coordinate = orientationHelper.getDecoratedEnd(view) + offset;
                        }

                    } else {
                        anchorInfo.position = anchorPos;
                        final int startRef = orientationHelper.getDecoratedStart(reference);
                        if (startRef > anchorInfo.coordinate) {
                            // move align up
                            offset = anchorInfo.coordinate - startRef;
                            offset -= (isStartLine ? 0 : mainGap);
                            anchorInfo.coordinate = orientationHelper.getDecoratedStart(view) + offset;
                        } else {
                            offset = -(isStartLine ? 0 : mainGap);
                            anchorInfo.coordinate = orientationHelper.getDecoratedStart(view) + offset;
                        }
                    }
                }
            }


            for (Span span : mSpans) {
                span.cacheReferenceLineAndClear(helper.getReverseLayout() ^ anchorInfo.layoutFromEnd, offset, orientationHelper);
            }
        }
    }


    @Override
    public void onItemsChanged(LayoutManagerHelper helper) {
        mLazySpanLookup.clear();
    }

    @Override
    public void onSaveState(Bundle bundle) {
        super.onSaveState(bundle);
        bundle.putIntArray(LOOKUP_BUNDLE_KEY, mLazySpanLookup.mData);
        // TODO: store span info
    }

    @Override
    public void onRestoreInstanceState(Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        mLazySpanLookup.mData = bundle.getIntArray(LOOKUP_BUNDLE_KEY);
    }


    @Override
    public void onOffsetChildrenVertical(int dy, LayoutManagerHelper helper) {
        super.onOffsetChildrenVertical(dy, helper);
        if (helper.getOrientation() == VERTICAL) {
            for (Span span : mSpans) {
                span.onOffset(dy);
            }
        }
    }

    @Override
    public void onOffsetChildrenHorizontal(int dx, LayoutManagerHelper helper) {
        super.onOffsetChildrenHorizontal(dx, helper);
        if (helper.getOrientation() == HORIZONTAL) {
            for (Span span : mSpans) {
                span.onOffset(dx);
            }
        }
    }


    // Package scoped to access from tests.
    static class Span {

        static final int INVALID_OFFSET = Integer.MIN_VALUE;
        private ArrayList<View> mViews = new ArrayList<View>();
        int mCachedStart = INVALID_LINE;
        int mCachedEnd = INVALID_LINE;
        int mDeletedSize = 0;
        final int mIndex;
        int mLastEdgeStart = INVALID_LINE;
        int mLastEdgeEnd = INVALID_LINE;

        private Span(int index) {
            mIndex = index;
        }

        void calculateCachedStart(@NonNull OrientationHelper helper) {
            if (mViews.size() == 0) {
                mCachedStart = INVALID_LINE;
            } else {
                final View startView = mViews.get(0);
                mCachedStart = helper.getDecoratedStart(startView);
            }
        }

        int getStartLine(OrientationHelper helper) {
            return getStartLine(INVALID_LINE, helper);
        }

        // Use this one when default value does not make sense and not having a value means a bug.
        int getStartLine(int def, OrientationHelper helper) {
            if (mCachedStart != INVALID_LINE) {
                return mCachedStart;
            }

            if (def != INVALID_LINE && mViews.size() == 0) {
                if (mLastEdgeEnd != INVALID_LINE)
                    return mLastEdgeEnd;
                return def;
            }

            calculateCachedStart(helper);
            return mCachedStart;
        }

        void calculateCachedEnd(OrientationHelper helper) {
            if (mViews.size() == 0) {
                mCachedEnd = INVALID_LINE;
            } else {
                final View endView = mViews.get(mViews.size() - 1);
                mCachedEnd = helper.getDecoratedEnd(endView);
            }
        }

        int getEndLine(OrientationHelper helper) {
            return getEndLine(INVALID_LINE, helper);
        }

        // Use this one when default value does not make sense and not having a value means a bug.
        int getEndLine(int def, OrientationHelper helper) {
            if (mCachedEnd != INVALID_LINE) {
                return mCachedEnd;
            }

            if (def != INVALID_LINE && mViews.size() == 0) {
                if (mLastEdgeStart != INVALID_LINE)
                    return mLastEdgeStart;
                return def;
            }

            calculateCachedEnd(helper);
            return mCachedEnd;
        }

        void prependToSpan(View view, OrientationHelper helper) {
            LayoutParams lp = getLayoutParams(view);
            mViews.add(0, view);
            mCachedStart = INVALID_LINE;
            if (mViews.size() == 1) {
                mCachedEnd = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize += helper.getDecoratedMeasurement(view);
            }
        }

        void appendToSpan(View view, OrientationHelper helper) {
            LayoutParams lp = getLayoutParams(view);
            mViews.add(view);
            mCachedEnd = INVALID_LINE;
            if (mViews.size() == 1) {
                mCachedStart = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize += helper.getDecoratedMeasurement(view);
            }
        }

        // Useful method to preserve positions on a re-layout.
        void cacheReferenceLineAndClear(boolean reverseLayout, int offset, OrientationHelper helper) {
            int reference;
            if (reverseLayout) {
                reference = getEndLine(helper);
            } else {
                reference = getStartLine(helper);
            }
            clear();
            if (reference == INVALID_LINE) {
                return;
            }
            if ((reverseLayout && reference < helper.getEndAfterPadding()) ||
                    (!reverseLayout && reference > helper.getStartAfterPadding())) {
                // return;
            }
            if (offset != INVALID_OFFSET) {
                reference += offset;
            }
            mCachedStart = mCachedEnd = reference;
            mLastEdgeStart = mLastEdgeEnd = INVALID_LINE;
        }

        void clear() {
            mViews.clear();
            invalidateCache();
            mDeletedSize = 0;
        }

        void invalidateCache() {
            mCachedStart = INVALID_LINE;
            mCachedEnd = INVALID_LINE;
            mLastEdgeEnd = INVALID_LINE;
            mLastEdgeStart = INVALID_LINE;
        }

        void setLine(int line) {
            mCachedEnd = mCachedStart = line;
            mLastEdgeStart = mLastEdgeEnd = INVALID_LINE;
        }

        void popEnd(OrientationHelper helper) {
            final int size = mViews.size();
            View end = mViews.remove(size - 1);
            final LayoutParams lp = getLayoutParams(end);
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize -= helper.getDecoratedMeasurement(end);
            }
            if (size == 1) {
                mCachedStart = INVALID_LINE;
            }
            mCachedEnd = INVALID_LINE;
        }

        boolean findEnd(View view) {
            final int size = mViews.size();
            if (size > 0) {
                return mViews.get(size - 1) == view;
            }
            return false;
        }


        void popStart(OrientationHelper helper) {
            View start = mViews.remove(0);
            final LayoutParams lp = getLayoutParams(start);
            if (mViews.size() == 0) {
                mCachedEnd = INVALID_LINE;
            }
            if (lp.isItemRemoved() || lp.isItemChanged()) {
                mDeletedSize -= helper.getDecoratedMeasurement(start);
            }
            mCachedStart = INVALID_LINE;
        }


        boolean findStart(View view) {
            final int size = mViews.size();
            if (size > 0) {
                return mViews.get(0) == view;
            }
            return false;
        }


        public int getDeletedSize() {
            return mDeletedSize;
        }

        LayoutParams getLayoutParams(View view) {
            return (LayoutParams) view.getLayoutParams();
        }

        void onOffset(int dt) {
            if (mLastEdgeStart != INVALID_LINE) {
                mLastEdgeStart += dt;
            }

            if (mCachedStart != INVALID_LINE) {
                mCachedStart += dt;
            }

            if (mLastEdgeEnd != INVALID_LINE)
                mLastEdgeEnd += dt;

            if (mCachedEnd != INVALID_LINE) {
                mCachedEnd += dt;
            }
        }

        // normalized offset is how much this span can scroll
        int getNormalizedOffset(int dt, int targetStart, int targetEnd, OrientationHelper helper) {
            if (mViews.size() == 0) {
                return 0;
            }
            if (dt < 0) {
                final int endSpace = getEndLine(0, helper) - targetEnd;
                if (endSpace <= 0) {
                    return 0;
                }
                return -dt > endSpace ? -endSpace : dt;
            } else {
                final int startSpace = targetStart - getStartLine(0, helper);
                if (startSpace <= 0) {
                    return 0;
                }
                return startSpace < dt ? startSpace : dt;
            }
        }

        /**
         * Returns if there is no child between start-end lines
         *
         * @param start The start line
         * @param end   The end line
         * @return true if a new child can be added between start and end
         */
        boolean isEmpty(int start, int end, OrientationHelper helper) {
            final int count = mViews.size();
            for (int i = 0; i < count; i++) {
                final View view = mViews.get(i);
                if (helper.getDecoratedStart(view) < end &&
                        helper.getDecoratedEnd(view) > start) {
                    return false;
                }
            }
            return true;
        }
    }


    /**
     * An array of mappings from adapter position to span.
     * This only grows when a write happens and it grows up to the size of the adapter.
     */
    static class LazySpanLookup {

        private static final int MIN_SIZE = 10;
        int[] mData;

        /**
         * returns end position for invalidation.
         */
        int invalidateAfter(int position) {
            if (mData == null) {
                return RecyclerView.NO_POSITION;
            }
            if (position >= mData.length) {
                return RecyclerView.NO_POSITION;
            }

            Arrays.fill(mData, position, mData.length, INVALID_SPAN_ID);
            return mData.length;
        }

        int getSpan(int position) {
            if (mData == null || position >= mData.length) {
                return INVALID_SPAN_ID;
            } else {
                return mData[position];
            }
        }

        void setSpan(int position, Span span) {
            ensureSize(position);
            mData[position] = span.mIndex;
        }

        int sizeForPosition(int position) {
            int len = mData.length;
            while (len <= position) {
                len *= 2;
            }
            return len;
        }

        void ensureSize(int position) {
            if (mData == null) {
                mData = new int[Math.max(position, MIN_SIZE) + 1];
                Arrays.fill(mData, INVALID_SPAN_ID);
            } else if (position >= mData.length) {
                int[] old = mData;
                mData = new int[sizeForPosition(position)];
                System.arraycopy(old, 0, mData, 0, old.length);
                Arrays.fill(mData, old.length, mData.length, INVALID_SPAN_ID);
            }
        }

        void clear() {
            if (mData != null) {
                Arrays.fill(mData, INVALID_SPAN_ID);
            }
        }

        void offsetForRemoval(int positionStart, int itemCount) {
            if (mData == null || positionStart >= mData.length) {
                return;
            }
            ensureSize(positionStart + itemCount);
            System.arraycopy(mData, positionStart + itemCount, mData, positionStart,
                    mData.length - positionStart - itemCount);
            Arrays.fill(mData, mData.length - itemCount, mData.length,
                    INVALID_SPAN_ID);
        }


        void offsetForAddition(int positionStart, int itemCount) {
            if (mData == null || positionStart >= mData.length) {
                return;
            }
            ensureSize(positionStart + itemCount);
            System.arraycopy(mData, positionStart, mData, positionStart + itemCount,
                    mData.length - positionStart - itemCount);
            Arrays.fill(mData, positionStart, positionStart + itemCount,
                    INVALID_SPAN_ID);
        }

    }

}
