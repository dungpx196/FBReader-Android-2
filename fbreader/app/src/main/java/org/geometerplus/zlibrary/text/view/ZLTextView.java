/*
 * Copyright (C) 2007-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.zlibrary.text.view;

import java.util.*;

import org.geometerplus.zlibrary.core.application.ZLApplication;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.image.ZLImageData;
import org.geometerplus.zlibrary.core.library.ZLibrary;
import org.geometerplus.zlibrary.core.util.RationalNumber;
import org.geometerplus.zlibrary.core.util.ZLColor;
import org.geometerplus.zlibrary.core.view.*;

import org.geometerplus.zlibrary.text.model.*;
import org.geometerplus.zlibrary.text.hyphenation.*;
import org.geometerplus.zlibrary.text.view.style.ZLTextStyleCollection;

public abstract class ZLTextView extends ZLTextViewBase {
	public interface ScrollingMode {
		int NO_OVERLAPPING = 0;
		int KEEP_LINES = 1;
		int SCROLL_LINES = 2;
		int SCROLL_PERCENTAGE = 3;
	};

	private ZLTextModel myModel;

	private interface SizeUnit {
		int PIXEL_UNIT = 0;
		int LINE_UNIT = 1;
	};

	private int myScrollingMode;
	private int myOverlappingValue;

	private ZLTextPage myPreviousPage = new ZLTextPage();
	private ZLTextPage myCurrentPage = new ZLTextPage();
	private ZLTextPage myNextPage = new ZLTextPage();

	private final HashMap<ZLTextLineInfo,ZLTextLineInfo> myLineInfoCache = new HashMap<ZLTextLineInfo,ZLTextLineInfo>();

	private ZLTextRegion.Soul myOutlinedRegionSoul;
	private boolean myShowOutline = true;

	private final ZLTextSelection mySelection = new ZLTextSelection(this);
	private final Set<ZLTextHighlighting> myHighlightings =
		Collections.synchronizedSet(new TreeSet<ZLTextHighlighting>());

	private CursorManager myCursorManager;

	public ZLTextView(ZLApplication application) {
		super(application);
	}

	public synchronized void setModel(ZLTextModel model) {
		myCursorManager = model != null ? new CursorManager(model, getExtensionManager()) : null;

		mySelection.clear();
		myHighlightings.clear();
		mySections.clear();
		myStartPages.clear();
		myEndPages.clear();

		myModel = model;
		myCurrentPage.reset();
		myPreviousPage.reset();
		myNextPage.reset();
		if (myModel != null) {
			final int paragraphsNumber = myModel.getParagraphsNumber();
			if (paragraphsNumber > 0) {
				myCurrentPage.moveStartCursor(myCursorManager.get(0));
			}
		}
		Application.getViewWidget().reset();
	}

	public final ZLTextModel getModel() {
		return myModel;
	}

	public ZLTextWordCursor getStartCursor() {
		if (myCurrentPage.StartCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		return myCurrentPage.StartCursor;
	}

	public ZLTextWordCursor getEndCursor() {
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		return myCurrentPage.EndCursor;
	}

	private synchronized void gotoMark(ZLTextMark mark) {
		if (mark == null) {
			return;
		}

		myPreviousPage.reset();
		myNextPage.reset();
		boolean doRepaint = false;
		if (myCurrentPage.StartCursor.isNull()) {
			doRepaint = true;
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.StartCursor.isNull()) {
			return;
		}
		if (myCurrentPage.StartCursor.getParagraphIndex() != mark.ParagraphIndex ||
			myCurrentPage.StartCursor.getMark().compareTo(mark) > 0) {
			doRepaint = true;
			gotoPosition(mark.ParagraphIndex, 0, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		while (mark.compareTo(myCurrentPage.EndCursor.getMark()) > 0) {
			doRepaint = true;
			turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (doRepaint) {
			if (myCurrentPage.StartCursor.isNull()) {
				preparePaintInfo(myCurrentPage);
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public synchronized void gotoHighlighting(ZLTextHighlighting highlighting) {
		myPreviousPage.reset();
		myNextPage.reset();
		boolean doRepaint = false;
		if (myCurrentPage.StartCursor.isNull()) {
			doRepaint = true;
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.StartCursor.isNull()) {
			return;
		}
		if (!highlighting.intersects(myCurrentPage)) {
			gotoPosition(highlighting.getStartPosition().getParagraphIndex(), 0, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (myCurrentPage.EndCursor.isNull()) {
			preparePaintInfo(myCurrentPage);
		}
		while (!highlighting.intersects(myCurrentPage)) {
			doRepaint = true;
			turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			preparePaintInfo(myCurrentPage);
		}
		if (doRepaint) {
			if (myCurrentPage.StartCursor.isNull()) {
				preparePaintInfo(myCurrentPage);
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public synchronized int search(final String text, boolean ignoreCase, boolean wholeText, boolean backward, boolean thisSectionOnly) {
		if (myModel == null || text.length() == 0) {
			return 0;
		}
		int startIndex = 0;
		int endIndex = myModel.getParagraphsNumber();
		if (thisSectionOnly) {
			// TODO: implement
		}
		int count = myModel.search(text, startIndex, endIndex, ignoreCase);
		myPreviousPage.reset();
		myNextPage.reset();
		if (!myCurrentPage.StartCursor.isNull()) {
			rebuildPaintInfo();
			if (count > 0) {
				ZLTextMark mark = myCurrentPage.StartCursor.getMark();
				gotoMark(wholeText ?
					(backward ? myModel.getLastMark() : myModel.getFirstMark()) :
					(backward ? myModel.getPreviousMark(mark) : myModel.getNextMark(mark)));
			}
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
		return count;
	}

	public boolean canFindNext() {
		final ZLTextWordCursor end = myCurrentPage.EndCursor;
		return !end.isNull() && (myModel != null) && (myModel.getNextMark(end.getMark()) != null);
	}

	public synchronized void findNext() {
		final ZLTextWordCursor end = myCurrentPage.EndCursor;
		if (!end.isNull()) {
			gotoMark(myModel.getNextMark(end.getMark()));
		}
	}

	public boolean canFindPrevious() {
		final ZLTextWordCursor start = myCurrentPage.StartCursor;
		return !start.isNull() && (myModel != null) && (myModel.getPreviousMark(start.getMark()) != null);
	}

	public synchronized void findPrevious() {
		final ZLTextWordCursor start = myCurrentPage.StartCursor;
		if (!start.isNull()) {
			gotoMark(myModel.getPreviousMark(start.getMark()));
		}
	}

	public void clearFindResults() {
		if (!findResultsAreEmpty()) {
			myModel.removeAllMarks();
			rebuildPaintInfo();
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public boolean findResultsAreEmpty() {
		return myModel == null || myModel.getMarks().isEmpty();
	}

	@Override
	public synchronized void onScrollingFinished(PageIndex pageIndex) {
		switch (pageIndex) {
			case current:
				break;
			case previous:
			{
				final ZLTextPage swap = myNextPage;
				myNextPage = myCurrentPage;
				myCurrentPage = myPreviousPage;
				myPreviousPage = swap;
				myPreviousPage.reset();
				if (myCurrentPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myNextPage);
					myCurrentPage.EndCursor.setCursor(myNextPage.StartCursor);
					myCurrentPage.PaintState = PaintStateEnum.END_IS_KNOWN;
				} else if (!myCurrentPage.EndCursor.isNull() &&
						   !myNextPage.StartCursor.isNull() &&
						   !myCurrentPage.EndCursor.samePositionAs(myNextPage.StartCursor)) {
					myNextPage.reset();
					myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
					myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
					Application.getViewWidget().reset();
				}
				break;
			}
			case next:
			{
				final ZLTextPage swap = myPreviousPage;
				myPreviousPage = myCurrentPage;
				myCurrentPage = myNextPage;
				myNextPage = swap;
				myNextPage.reset();
				switch (myCurrentPage.PaintState) {
					case PaintStateEnum.NOTHING_TO_PAINT:
						preparePaintInfo(myPreviousPage);
						myCurrentPage.StartCursor.setCursor(myPreviousPage.EndCursor);
						myCurrentPage.PaintState = PaintStateEnum.START_IS_KNOWN;
						break;
					case PaintStateEnum.READY:
						myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
						myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
						break;
				}
				break;
			}
		}
	}

	public boolean removeHighlightings(Class<? extends ZLTextHighlighting> type) {
		boolean result = false;
		synchronized (myHighlightings) {
			for (Iterator<ZLTextHighlighting> it = myHighlightings.iterator(); it.hasNext(); ) {
				final ZLTextHighlighting h = it.next();
				if (type.isInstance(h)) {
					it.remove();
					result = true;
				}
			}
		}
		return result;
	}

	public void highlight(ZLTextPosition start, ZLTextPosition end) {
		removeHighlightings(ZLTextManualHighlighting.class);
		addHighlighting(new ZLTextManualHighlighting(this, start, end));
	}

	public final void addHighlighting(ZLTextHighlighting h) {
		myHighlightings.add(h);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	public final void addHighlightings(Collection<ZLTextHighlighting> hilites) {
		myHighlightings.addAll(hilites);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	public void clearHighlighting() {
		if (removeHighlightings(ZLTextManualHighlighting.class)) {
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	protected void moveSelectionCursorTo(SelectionCursor.Which which, int x, int y) {
		y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
		mySelection.setCursorInMovement(which, x, y);
		mySelection.expandTo(myCurrentPage, x, y);
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	protected void releaseSelectionCursor() {
		mySelection.stop();
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
	}

	protected SelectionCursor.Which getSelectionCursorInMovement() {
		return mySelection.getCursorInMovement();
	}

	private ZLTextSelection.Point getSelectionCursorPoint(ZLTextPage page, SelectionCursor.Which which) {
		if (which == null) {
			return null;
		}

		if (which == mySelection.getCursorInMovement()) {
			return mySelection.getCursorInMovementPoint();
		}

		if (which == SelectionCursor.Which.Left) {
			if (mySelection.hasPartBeforePage(page)) {
				return null;
			}
			final ZLTextElementArea area = mySelection.getStartArea(page);
			if (area != null) {
				return new ZLTextSelection.Point(area.XStart, (area.YStart + area.YEnd) / 2);
			}
		} else {
			if (mySelection.hasPartAfterPage(page)) {
				return null;
			}
			final ZLTextElementArea area = mySelection.getEndArea(page);
			if (area != null) {
				return new ZLTextSelection.Point(area.XEnd, (area.YStart + area.YEnd) / 2);
			}
		}
		return null;
	}

	private float distance2ToCursor(int x, int y, SelectionCursor.Which which) {
		final ZLTextSelection.Point point = getSelectionCursorPoint(myCurrentPage, which);
		if (point == null) {
			return Float.MAX_VALUE;
		}
		final float dX = x - point.X;
		final float dY = y - point.Y;
		return dX * dX + dY * dY;
	}

	protected SelectionCursor.Which findSelectionCursor(int x, int y) {
		return findSelectionCursor(x, y, Float.MAX_VALUE);
	}

	protected SelectionCursor.Which findSelectionCursor(int x, int y, float maxDistance2) {
		if (mySelection.isEmpty()) {
			return null;
		}

		final float leftDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Left);
		final float rightDistance2 = distance2ToCursor(x, y, SelectionCursor.Which.Right);

		if (rightDistance2 < leftDistance2) {
			return rightDistance2 <= maxDistance2 ? SelectionCursor.Which.Right : null;
		} else {
			return leftDistance2 <= maxDistance2 ? SelectionCursor.Which.Left : null;
		}
	}

	private void drawSelectionCursor(ZLPaintContext context, ZLTextPage page, SelectionCursor.Which which) {
		final ZLTextSelection.Point pt = getSelectionCursorPoint(page, which);
		if (pt != null) {
			SelectionCursor.draw(context, which, pt.X, pt.Y, getSelectionBackgroundColor());
		}
	}

	@Override
	public synchronized void preparePage(ZLPaintContext context, PageIndex pageIndex) {
		setContext(context);
		preparePaintInfo(getPage(pageIndex));
	}

	@Override
	public synchronized void paint(ZLPaintContext context, PageIndex pageIndex) {
		setContext(context);
		final ZLFile wallpaper = getWallpaperFile();
		if (wallpaper != null) {
			context.clear(wallpaper, getFillMode());
		} else {
			context.clear(getBackgroundColor());
		}

		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return;
		}

		ZLTextPage page;
		switch (pageIndex) {
			default:
			case current:
				page = myCurrentPage;
				break;
			case previous:
				page = myPreviousPage;
				if (myPreviousPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myCurrentPage);
					myPreviousPage.EndCursor.setCursor(myCurrentPage.StartCursor);
					myPreviousPage.PaintState = PaintStateEnum.END_IS_KNOWN;
				}
				break;
			case next:
				page = myNextPage;
				if (myNextPage.PaintState == PaintStateEnum.NOTHING_TO_PAINT) {
					preparePaintInfo(myCurrentPage);
					myNextPage.StartCursor.setCursor(myCurrentPage.EndCursor);
					myNextPage.PaintState = PaintStateEnum.START_IS_KNOWN;
				}
		}

		page.TextElementMap.clear();

		preparePaintInfo(page);

		if (page.StartCursor.isNull() || page.EndCursor.isNull()) {
			return;
		}

		final ArrayList<ZLTextLineInfo> lineInfos = page.LineInfos;
		final int[] labels = new int[lineInfos.size() + 1];
		int x = getLeftMargin();
		int y = getTopMargin();
		int index = 0;
		int columnIndex = 0;
		ZLTextLineInfo previousInfo = null;
		for (ZLTextLineInfo info : lineInfos) {
			info.adjust(previousInfo);
			prepareTextLine(page, info, x, y, columnIndex);
			y += info.Height + info.Descent + info.VSpaceAfter;
			labels[++index] = page.TextElementMap.size();
			if (index == page.Column0Height) {
				y = getTopMargin();
				x += page.getTextWidth() + getSpaceBetweenColumns();
				columnIndex = 1;
			}
			previousInfo = info;
		}

		final List<ZLTextHighlighting> hilites = findHilites(page);

		x = getLeftMargin();
		y = getTopMargin();
		index = 0;
		for (ZLTextLineInfo info : lineInfos) {
			drawTextLine(page, hilites, info, labels[index], labels[index + 1]);
			y += info.Height + info.Descent + info.VSpaceAfter;
			++index;
			if (index == page.Column0Height) {
				y = getTopMargin();
				x += page.getTextWidth() + getSpaceBetweenColumns();
			}
		}

		for (ZLTextHighlighting h : hilites) {
			int mode = Hull.DrawMode.None;

			final ZLColor bgColor = h.getBackgroundColor();
			if (bgColor != null) {
				context.setFillColor(bgColor, 128);
				mode |= Hull.DrawMode.Fill;
			}

			final ZLColor outlineColor = h.getOutlineColor();
			if (outlineColor != null) {
				context.setLineColor(outlineColor);
				mode |= Hull.DrawMode.Outline;
			}

			if (mode != Hull.DrawMode.None) {
				h.hull(page).draw(getContext(), mode);
			}
		}

		final ZLTextRegion outlinedElementRegion = getOutlinedRegion(page);
		if (outlinedElementRegion != null && myShowOutline) {
			context.setLineColor(getSelectionBackgroundColor());
			outlinedElementRegion.hull().draw(context, Hull.DrawMode.Outline);
		}

		drawSelectionCursor(context, page, SelectionCursor.Which.Left);
		drawSelectionCursor(context, page, SelectionCursor.Which.Right);
	}

	private ZLTextPage getPage(PageIndex pageIndex) {
		switch (pageIndex) {
			default:
			case current:
				return myCurrentPage;
			case previous:
				return myPreviousPage;
			case next:
				return myNextPage;
		}
	}

	private synchronized int sizeOfFullText() {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 1;
		}
		return myModel.getTextLength(myModel.getParagraphsNumber() - 1);
	}

	private final synchronized int getCurrentCharNumber(PageIndex pageIndex, boolean startNotEndOfPage) {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 0;
		}
		final ZLTextPage page = getPage(pageIndex);
		preparePaintInfo(page);
		if (startNotEndOfPage) {
			return Math.max(0, sizeOfTextBeforeCursor(page.StartCursor));
		} else {
			int end = sizeOfTextBeforeCursor(page.EndCursor);
			if (end == -1) {
				end = myModel.getTextLength(myModel.getParagraphsNumber() - 1) - 1;
			}
			return Math.max(1, end);
		}
	}

	@Override
	public final synchronized int getScrollbarFullSize() {
		return sizeOfFullText();
	}

	@Override
	public final synchronized int getScrollbarThumbPosition(PageIndex pageIndex) {
		return scrollbarMode() == ScrollbarMode.asProgress ? 0 : getCurrentCharNumber(pageIndex, true);
	}

	@Override
	public final synchronized int getScrollbarThumbLength(PageIndex pageIndex) {
		int start = scrollbarMode() == ScrollbarMode.asProgress
			? 0 : getCurrentCharNumber(pageIndex, true);
		int end = getCurrentCharNumber(pageIndex, false);
		return Math.max(1, end - start);
	}

	private int sizeOfTextBeforeCursor(ZLTextWordCursor wordCursor) {
		final ZLTextParagraphCursor paragraphCursor = wordCursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return -1;
		}
		final int paragraphIndex = paragraphCursor.Index;
		int sizeOfText = myModel.getTextLength(paragraphIndex - 1);
		final int paragraphLength = paragraphCursor.getParagraphLength();
		if (paragraphLength > 0) {
			sizeOfText += (int)(.5f + 1.f *
				(myModel.getTextLength(paragraphIndex) - sizeOfText)
				* wordCursor.getElementIndex()
				/ paragraphLength
			);
		}
		return sizeOfText;
	}

	private int myStoredTextWidth, myStoredTextHeight;
	private void updateTextParams() {
		final int textWidth = getTextColumnWidth();
		final int textHeight = getTextAreaHeight();
		if (textWidth != myStoredTextWidth || textHeight != myStoredTextHeight) {
			myStoredTextWidth = textWidth;
			myStoredTextHeight = textHeight;
			myCharsPerPage = -1f;
			myStartPages.clear();
			myEndPages.clear();
		}
	}

	public static class PagePosition {
		public final int Current;
		public final int Total;

		PagePosition(int current, int total) {
			Current = current;
			Total = total;
		}
	}

	private static class SectionInfo {
		final int StartParagraph;
		final int EndParagraph;
		final int SizeBefore;
		final int TextSize;
		int PagesBefore;

		SectionInfo(int start, int end, int sizeBefore, int size) {
			StartParagraph = start;
			EndParagraph = end;
			SizeBefore = sizeBefore;
			TextSize = size;
		}
	};

	private final List<SectionInfo> mySections = new ArrayList<SectionInfo>();
	private volatile SectionInfo myLargestSection;
	private volatile int myMaxParagraphTextSize;

	private static final int MAX_PRECOMPUTED_PAGES = 5;
	private final List<Integer> myStartPages = new ArrayList<Integer>(MAX_PRECOMPUTED_PAGES);
	private final List<Integer> myEndPages = new ArrayList<Integer>(MAX_PRECOMPUTED_PAGES);
	private float myCharsPerPage;
	private int myTotalPages;
	private synchronized void precomputePagePositions() {
		updateTextParams();
		if (!myStartPages.isEmpty()) {
			return;
		}

		if (mySections.isEmpty()) {
			final int paraNumber = myModel.getParagraphsNumber();

			int prevSize = 0;
			myMaxParagraphTextSize = 0;
			for (int i = 0; i < paraNumber; ++i) {
				final int size = myModel.getTextLength(i);
				myMaxParagraphTextSize = Math.max(size - prevSize, myMaxParagraphTextSize);
				prevSize = size;
			}

			int start = 0;
			prevSize = 0;
			for (int i = 0; i < paraNumber; ++i) {
				if (myModel.getParagraphKind(i) == ZLTextParagraph.Kind.END_OF_SECTION_PARAGRAPH) {
					final int size = myModel.getTextLength(i);
					mySections.add(new SectionInfo(start, i, prevSize, size - prevSize));
					prevSize = size;
					start = i + 1;
				}
			}
			if (start < paraNumber) {
				mySections.add(new SectionInfo(
					start, paraNumber - 1, prevSize, sizeOfFullText() - prevSize
				));
			}
			int maxSize = -1;
			for (SectionInfo section : mySections) {
				if (section.TextSize > maxSize) {
					myLargestSection = section;
					maxSize = section.TextSize;
				}
			}
		}

		if (myLargestSection == null) {
			myCharsPerPage = 1000;
			myTotalPages = 1;
			return;
		}

		ZLTextWordCursor cursor = myCurrentPage.StartCursor;
		if (cursor.isNull()) {
			cursor = myCurrentPage.EndCursor;
		}
		if (cursor.isNull()) {
			myCharsPerPage = 1000;
			myTotalPages = 1;
			return;
		}

		final ZLTextPage testPage = new ZLTextPage();
		testPage.StartCursor.setCursor(cursor);
		testPage.moveStartCursor(0, 0, 0);
		for (int i = 0; i < MAX_PRECOMPUTED_PAGES; ++i) {
			testPage.PaintState = PaintStateEnum.START_IS_KNOWN;
			preparePaintInfo(testPage, false, false);
			myStartPages.add(sizeOfTextBeforeCursor(testPage.EndCursor));
			if (testPage.EndCursor.isEndOfText()) {
				break;
			}
			testPage.StartCursor.setCursor(testPage.EndCursor);
		}

		final ArrayList<Integer> test = new ArrayList<Integer>();
		testPage.moveStartCursor(myLargestSection.StartParagraph, 0, 0);
		int prev = sizeOfTextBeforeCursor(testPage.StartCursor);
		for (int i = 0; i < MAX_PRECOMPUTED_PAGES; ++i) {
			testPage.PaintState = PaintStateEnum.START_IS_KNOWN;
			preparePaintInfo(testPage, false, false);
			final int size = sizeOfTextBeforeCursor(testPage.EndCursor);
			test.add(size - prev);
			prev = size;
			int para = testPage.EndCursor.getParagraphIndex();
			if (testPage.EndCursor.isEndOfParagraph()) {
				++para;
			}
			if (para > myLargestSection.EndParagraph) {
				break;
			}
			testPage.StartCursor.setCursor(testPage.EndCursor);
		}
		Collections.sort(test);
		if (test.size() == 0) {
			myCharsPerPage = 1000;
		} else {
			myCharsPerPage = test.get(test.size() - 1);
		}

		int pages = 0;
		for (SectionInfo section : mySections) {
			section.PagesBefore = pages;
			pages += (section.TextSize + myCharsPerPage - 1) / myCharsPerPage;
		}
		myTotalPages = pages;

		if (myMaxParagraphTextSize < MAX_PRECOMPUTED_PAGES * myCharsPerPage) {
			testPage.moveEndCursor(myModel.getParagraphsNumber(), 0, 0);
			for (int i = 0; i < MAX_PRECOMPUTED_PAGES; ++i) {
				testPage.PaintState = PaintStateEnum.END_IS_KNOWN;
				preparePaintInfo(testPage, false, true);
				myEndPages.add(sizeOfTextBeforeCursor(testPage.StartCursor));
				if (testPage.StartCursor.isStartOfText()) {
					break;
				}
				testPage.EndCursor.setCursor(testPage.StartCursor);
			}
		}

		if (myStartPages.isEmpty()) {
			myTotalPages = 1;
		} else if (!myEndPages.isEmpty()) {
			final int lastStartPagesChar = myStartPages.get(myStartPages.size() - 1);
			final int firstEndPagesChar = myEndPages.get(myEndPages.size() - 1);
			if (lastStartPagesChar >= firstEndPagesChar) {
				myTotalPages = myStartPages.size();
				if (lastStartPagesChar < sizeOfFullText()) {
					++myTotalPages;
					for (int c : myEndPages) {
						if (c >= lastStartPagesChar) {
							++myTotalPages;
						} else {
							break;
						}
					}
				}
			}
		}
	}

	public final synchronized PagePosition pagePosition() {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return new PagePosition(1, 1);
		}

		precomputePagePositions();
		if (myCurrentPage.EndCursor.isEndOfText()) {
			return new PagePosition(myTotalPages, myTotalPages);
		}

		return new PagePosition(
			pageNoFromChars(getCurrentCharNumber(PageIndex.current, false)), myTotalPages
		);
	}

	public final synchronized int pageNoFromParagraph(int paragraphIndex) {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return 1;
		}

		precomputePagePositions();
		return pageNoFromChars(myModel.getTextLength(paragraphIndex - 1)) + 1;
	}

	private final synchronized int pageNoFromChars(int chars) {
		for (int i = 0; i < myStartPages.size(); ++i) {
			if (chars <= myStartPages.get(i)) {
				return i + 1;
			}
		}
		for (int i = 0; i < myEndPages.size(); ++i) {
			if (chars >= myEndPages.get(i)) {
				return myTotalPages - i - 1;
			}
		}

		final SectionInfo fake = new SectionInfo(0, 0, chars, 0);
		final int sectionIndex = Collections.binarySearch(
			mySections, fake, new Comparator<SectionInfo> () {
				public int compare(SectionInfo info0, SectionInfo info1) {
					return info0.SizeBefore - info1.SizeBefore;
				}
			}
		);
		final SectionInfo section =
			mySections.get(sectionIndex >= 0 ? sectionIndex : Math.max(0, - sectionIndex - 2));
		final int page =
			section.PagesBefore + (int)(.5f + (chars - section.SizeBefore) / myCharsPerPage);
		return Math.max(myStartPages.size() + 1, Math.min(myTotalPages - myEndPages.size(), page));
	}

	public final RationalNumber getProgress() {
		final PagePosition position = pagePosition();
		return RationalNumber.create(position.Current, position.Total);
	}

	public final synchronized void gotoPage(int page) {
		if (myModel == null || myModel.getParagraphsNumber() == 0) {
			return;
		}

		precomputePagePositions();
		final float textSize = page * myCharsPerPage;

		int intTextSize = (int) textSize;
		int paragraphIndex = myModel.findParagraphByTextLength(intTextSize);

		if (paragraphIndex > 0 && myModel.getTextLength(paragraphIndex) > intTextSize) {
			--paragraphIndex;
		}
		intTextSize = myModel.getTextLength(paragraphIndex);

		int sizeOfTextBefore = myModel.getTextLength(paragraphIndex - 1);
		while (paragraphIndex > 0 && intTextSize == sizeOfTextBefore) {
			--paragraphIndex;
			intTextSize = sizeOfTextBefore;
			sizeOfTextBefore = myModel.getTextLength(paragraphIndex - 1);
		}

		final int paragraphLength = intTextSize - sizeOfTextBefore;

		final int wordIndex;
		if (paragraphLength == 0) {
			wordIndex = 0;
		} else {
			preparePaintInfo(myCurrentPage);
			final ZLTextWordCursor cursor = new ZLTextWordCursor(myCurrentPage.EndCursor);
			cursor.moveToParagraph(paragraphIndex);
			wordIndex = cursor.getParagraphCursor().getParagraphLength();
		}

		gotoPositionByEnd(paragraphIndex, wordIndex, 0);

		PagePosition position = pagePosition();
		if (position.Current > page) {
			for (; position.Current > page; position = pagePosition()) {
				preparePaintInfo(myCurrentPage);
				if (myCurrentPage.StartCursor.isStartOfText()) {
					break;
				}
				turnPage(false, ScrollingMode.NO_OVERLAPPING, 1);
			}
		} else if (position.Current < page) {
			for (; position.Current < page; position = pagePosition()) {
				preparePaintInfo(myCurrentPage);
				if (myCurrentPage.EndCursor.isEndOfText()) {
					break;
				}
				turnPage(true, ScrollingMode.NO_OVERLAPPING, 1);
			}
		}
	}

	public void gotoHome() {
		final ZLTextWordCursor cursor = getStartCursor();
		if (!cursor.isNull() && cursor.isStartOfParagraph() && cursor.getParagraphIndex() == 0) {
			return;
		}
		gotoPosition(0, 0, 0);
		preparePaintInfo();
	}

	private List<ZLTextHighlighting> findHilites(ZLTextPage page) {
		final LinkedList<ZLTextHighlighting> hilites = new LinkedList<ZLTextHighlighting>();
		if (mySelection.intersects(page)) {
			hilites.add(mySelection);
		}
		synchronized (myHighlightings) {
			for (ZLTextHighlighting h : myHighlightings) {
				if (h.intersects(page)) {
					hilites.add(h);
				}
			}
		}
		return hilites;
	}

	protected abstract ZLPaintContext.ColorAdjustingMode getAdjustingModeForImages();

	private static final char[] SPACE = new char[] { ' ' };
	private void drawTextLine(ZLTextPage page, List<ZLTextHighlighting> hilites, ZLTextLineInfo info, int from, int to) {
		final ZLPaintContext context = getContext();
		final ZLTextParagraphCursor paragraph = info.ParagraphCursor;
		int index = from;
		final int endElementIndex = info.EndElementIndex;
		int charIndex = info.RealStartCharIndex;
		final List<ZLTextElementArea> pageAreas = page.TextElementMap.areas();
		if (to > pageAreas.size()) {
			return;
		}
		for (int wordIndex = info.RealStartElementIndex; wordIndex != endElementIndex && index < to; ++wordIndex, charIndex = 0) {
			final ZLTextElement element = paragraph.getElement(wordIndex);
			final ZLTextElementArea area = pageAreas.get(index);
			if (element == area.Element) {
				++index;
				if (area.ChangeStyle) {
					setTextStyle(area.Style);
				}
				final int areaX = area.XStart;
				final int areaY = area.YEnd - getElementDescent(element) - getTextStyle().getVerticalAlign(metrics());
				if (element instanceof ZLTextWord) {
					final ZLTextPosition pos =
						new ZLTextFixedPosition(info.ParagraphCursor.Index, wordIndex, 0);
					final ZLTextHighlighting hl = getWordHilite(pos, hilites);
					final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
					drawWord(
						areaX, areaY, (ZLTextWord)element, charIndex, -1, false,
						hlColor != null ? hlColor : getTextColor(getTextStyle().Hyperlink)
					);
				} else if (element instanceof ZLTextImageElement) {
					final ZLTextImageElement imageElement = (ZLTextImageElement)element;
					context.drawImage(
						areaX, areaY,
						imageElement.ImageData,
						getTextAreaSize(),
						getScalingType(imageElement),
						getAdjustingModeForImages()
					);
				} else if (element instanceof ZLTextVideoElement) {
					// TODO: draw
					context.setLineColor(getTextColor(ZLTextHyperlink.NO_LINK));
					context.setFillColor(new ZLColor(127, 127, 127));
					final int xStart = area.XStart + 10;
					final int xEnd = area.XEnd - 10;
					final int yStart = area.YStart + 10;
					final int yEnd = area.YEnd - 10;
					context.fillRectangle(xStart, yStart, xEnd, yEnd);
					context.drawLine(xStart, yStart, xStart, yEnd);
					context.drawLine(xStart, yEnd, xEnd, yEnd);
					context.drawLine(xEnd, yEnd, xEnd, yStart);
					context.drawLine(xEnd, yStart, xStart, yStart);
					final int l = xStart + (xEnd - xStart) * 7 / 16;
					final int r = xStart + (xEnd - xStart) * 10 / 16;
					final int t = yStart + (yEnd - yStart) * 2 / 6;
					final int b = yStart + (yEnd - yStart) * 4 / 6;
					final int c = yStart + (yEnd - yStart) / 2;
					context.setFillColor(new ZLColor(196, 196, 196));
					context.fillPolygon(new int[] { l, l, r }, new int[] { t, b, c });
				} else if (element instanceof ExtensionElement) {
					((ExtensionElement)element).draw(context, area);
				} else if (element == ZLTextElement.HSpace || element == ZLTextElement.NBSpace) {
					final int cw = context.getSpaceWidth();
					for (int len = 0; len < area.XEnd - area.XStart; len += cw) {
						context.drawString(areaX + len, areaY, SPACE, 0, 1);
					}
				}
			}
		}
		if (index != to) {
			ZLTextElementArea area = pageAreas.get(index++);
			if (area.ChangeStyle) {
				setTextStyle(area.Style);
			}
			final int start = info.StartElementIndex == info.EndElementIndex
				? info.StartCharIndex : 0;
			final int len = info.EndCharIndex - start;
			final ZLTextWord word = (ZLTextWord)paragraph.getElement(info.EndElementIndex);
			final ZLTextPosition pos =
				new ZLTextFixedPosition(info.ParagraphCursor.Index, info.EndElementIndex, 0);
			final ZLTextHighlighting hl = getWordHilite(pos, hilites);
			final ZLColor hlColor = hl != null ? hl.getForegroundColor() : null;
			drawWord(
				area.XStart, area.YEnd - context.getDescent() - getTextStyle().getVerticalAlign(metrics()),
				word, start, len, area.AddHyphenationSign,
				hlColor != null ? hlColor : getTextColor(getTextStyle().Hyperlink)
			);
		}
	}

	private ZLTextHighlighting getWordHilite(ZLTextPosition pos, List<ZLTextHighlighting> hilites) {
		for (ZLTextHighlighting h : hilites) {
			if (h.getStartPosition().compareToIgnoreChar(pos) <= 0
				&& pos.compareToIgnoreChar(h.getEndPosition()) <= 0) {
				return h;
			}
		}
		return null;
	}

	private void buildInfos(ZLTextPage page, ZLTextWordCursor start, ZLTextWordCursor result) {
		result.setCursor(start);
		int textAreaHeight = page.getTextHeight();
		page.LineInfos.clear();
		page.Column0Height = 0;
		boolean nextParagraph;
		ZLTextLineInfo info = null;
		do {
			final ZLTextLineInfo previousInfo = info;
			resetTextStyle();
			final ZLTextParagraphCursor paragraphCursor = result.getParagraphCursor();
			final int wordIndex = result.getElementIndex();
			applyStyleChanges(paragraphCursor, 0, wordIndex);
			info = new ZLTextLineInfo(paragraphCursor, wordIndex, result.getCharIndex(), getTextStyle());
			final int endIndex = info.ParagraphCursorLength;
			while (info.EndElementIndex != endIndex) {
				info = processTextLine(page, paragraphCursor, info.EndElementIndex, info.EndCharIndex, endIndex, previousInfo);
				textAreaHeight -= info.Height + info.Descent;
				if (textAreaHeight < 0 && page.LineInfos.size() > page.Column0Height) {
					if (page.Column0Height == 0 && page.twoColumnView()) {
						textAreaHeight = page.getTextHeight();
						textAreaHeight -= info.Height + info.Descent;
						page.Column0Height = page.LineInfos.size();
					} else {
						break;
					}
				}
				textAreaHeight -= info.VSpaceAfter;
				result.moveTo(info.EndElementIndex, info.EndCharIndex);
				page.LineInfos.add(info);
				if (textAreaHeight < 0) {
					if (page.Column0Height == 0 && page.twoColumnView()) {
						textAreaHeight = page.getTextHeight();
						page.Column0Height = page.LineInfos.size();
					} else {
						break;
					}
				}
			}
			nextParagraph = result.isEndOfParagraph() && result.nextParagraph();
			if (nextParagraph && result.getParagraphCursor().isEndOfSection()) {
				if (page.Column0Height == 0 && page.twoColumnView() && !page.LineInfos.isEmpty()) {
					textAreaHeight = page.getTextHeight();
					page.Column0Height = page.LineInfos.size();
				}
			}
		} while (nextParagraph && textAreaHeight >= 0 &&
				 (!result.getParagraphCursor().isEndOfSection() ||
				  page.LineInfos.size() == page.Column0Height)
				);
		resetTextStyle();
	}

	private boolean isHyphenationPossible() {
		return getTextStyleCollection().getBaseStyle().AutoHyphenationOption.getValue()
			&& getTextStyle().allowHyphenations();
	}

	private volatile ZLTextWord myCachedWord;
	private volatile ZLTextHyphenationInfo myCachedInfo;
	private final synchronized ZLTextHyphenationInfo getHyphenationInfo(ZLTextWord word) {
		if (myCachedWord != word) {
			myCachedWord = word;
			myCachedInfo = ZLTextHyphenator.Instance().getInfo(word);
		}
		return myCachedInfo;
	}

	private ZLTextLineInfo processTextLine(
		ZLTextPage page,
		ZLTextParagraphCursor paragraphCursor,
		final int startIndex,
		final int startCharIndex,
		final int endIndex,
		ZLTextLineInfo previousInfo
	) {
		final ZLTextLineInfo info = processTextLineInternal(
			page, paragraphCursor, startIndex, startCharIndex, endIndex, previousInfo
		);
		if (info.EndElementIndex == startIndex && info.EndCharIndex == startCharIndex) {
			info.EndElementIndex = paragraphCursor.getParagraphLength();
			info.EndCharIndex = 0;
			// TODO: add error element
		}
		return info;
	}

	private ZLTextLineInfo processTextLineInternal(
		ZLTextPage page,
		ZLTextParagraphCursor paragraphCursor,
		final int startIndex,
		final int startCharIndex,
		final int endIndex,
		ZLTextLineInfo previousInfo
	) {
		final ZLPaintContext context = getContext();
		final ZLTextLineInfo info = new ZLTextLineInfo(paragraphCursor, startIndex, startCharIndex, getTextStyle());
		final ZLTextLineInfo cachedInfo = myLineInfoCache.get(info);
		if (cachedInfo != null) {
			cachedInfo.adjust(previousInfo);
			applyStyleChanges(paragraphCursor, startIndex, cachedInfo.EndElementIndex);
			return cachedInfo;
		}

		int currentElementIndex = startIndex;
		int currentCharIndex = startCharIndex;
		final boolean isFirstLine = startIndex == 0 && startCharIndex == 0;

		if (isFirstLine) {
			ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			while (isStyleChangeElement(element)) {
				applyStyleChangeElement(element);
				++currentElementIndex;
				currentCharIndex = 0;
				if (currentElementIndex == endIndex) {
					break;
				}
				element = paragraphCursor.getElement(currentElementIndex);
			}
			info.StartStyle = getTextStyle();
			info.RealStartElementIndex = currentElementIndex;
			info.RealStartCharIndex = currentCharIndex;
		}

		ZLTextStyle storedStyle = getTextStyle();

		final int maxWidth = page.getTextWidth() - storedStyle.getRightIndent(metrics());
		info.LeftIndent = storedStyle.getLeftIndent(metrics());
		if (isFirstLine && storedStyle.getAlignment() != ZLTextAlignmentType.ALIGN_CENTER) {
			info.LeftIndent += storedStyle.getFirstLineIndent(metrics());
		}
		if (info.LeftIndent > maxWidth - 20) {
			info.LeftIndent = maxWidth * 3 / 4;
		}

		info.Width = info.LeftIndent;

		if (info.RealStartElementIndex == endIndex) {
			info.EndElementIndex = info.RealStartElementIndex;
			info.EndCharIndex = info.RealStartCharIndex;
			return info;
		}

		int newWidth = info.Width;
		int newHeight = info.Height;
		int newDescent = info.Descent;
		boolean wordOccurred = false;
		boolean isVisible = false;
		int lastSpaceWidth = 0;
		int internalSpaceCounter = 0;
		boolean removeLastSpace = false;

		class WordInfo {
			final ZLTextWord Word;
			final int ElementIndex;
			final int StartCharIndex;

			final int Width;
			final int Height;
			final int Descent;

			final int SpaceCounter;

			final ZLTextStyle Style;

			WordInfo(ZLTextWord word, int elementIndex, int startCharIndex, int width, int height, int descent, int spaceCounter, ZLTextStyle style) {
				Word = word;
				ElementIndex = elementIndex;
				StartCharIndex = startCharIndex;
				Width = width;
				Height = height;
				Descent = descent;
				SpaceCounter = spaceCounter;
				Style = style;
			}
		}
		final ArrayList<WordInfo> words = new ArrayList<WordInfo>();

		do {
			ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			newWidth += getElementWidth(element, currentCharIndex);
			newHeight = Math.max(newHeight, getElementHeight(element));
			newDescent = Math.max(newDescent, getElementDescent(element));
			if (element == ZLTextElement.HSpace) {
				if (wordOccurred) {
					wordOccurred = false;
					++internalSpaceCounter;
					lastSpaceWidth = context.getSpaceWidth();
					newWidth += lastSpaceWidth;
				}
			} else if (element == ZLTextElement.NBSpace) {
				wordOccurred = true;
				++internalSpaceCounter;
				newWidth += context.getSpaceWidth();
			} else if (element instanceof ZLTextWord) {
				wordOccurred = true;
				isVisible = true;
			} else if (element instanceof ZLTextImageElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (element instanceof ZLTextVideoElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (element instanceof ExtensionElement) {
				wordOccurred = true;
				isVisible = true;
			} else if (isStyleChangeElement(element)) {
				applyStyleChangeElement(element);
			}
			if (newWidth > maxWidth) {
				if (info.EndElementIndex != startIndex || element instanceof ZLTextWord) {
					break;
				}
			}
			final ZLTextElement previousElement = element;
			final int previousStartCharIndex = currentCharIndex;
			++currentElementIndex;
			currentCharIndex = 0;
			boolean allowBreak = currentElementIndex == endIndex;
			if (!allowBreak) {
				element = paragraphCursor.getElement(currentElementIndex);
				allowBreak =
					previousElement != ZLTextElement.NBSpace &&
					element != ZLTextElement.NBSpace &&
					(!(element instanceof ZLTextWord) || previousElement instanceof ZLTextWord) &&
					!(element instanceof ZLTextImageElement) &&
					!(element instanceof ZLTextControlElement);
			}
			if (allowBreak) {
				words.clear();
				info.IsVisible = isVisible;
				info.Width = newWidth;
				if (info.Height < newHeight) {
					info.Height = newHeight;
				}
				if (info.Descent < newDescent) {
					info.Descent = newDescent;
				}
				info.EndElementIndex = currentElementIndex;
				info.EndCharIndex = currentCharIndex;
				info.SpaceCounter = internalSpaceCounter;
				storedStyle = getTextStyle();
				removeLastSpace = !wordOccurred && internalSpaceCounter > 0;
			} else if (previousElement instanceof ZLTextWord) {
				words.add(new WordInfo(
					(ZLTextWord)previousElement,
					currentElementIndex - 1, previousStartCharIndex,
					newWidth, newHeight, newDescent,
					internalSpaceCounter, getTextStyle()
				));
			}
		} while (currentElementIndex != endIndex);

		if (currentElementIndex != endIndex &&
			(isHyphenationPossible() || info.EndElementIndex == startIndex)) {
			final ZLTextElement element = paragraphCursor.getElement(currentElementIndex);
			boolean hyphenated = false;
			if (element instanceof ZLTextWord) {
				final ZLTextWord word = (ZLTextWord)element;
				newWidth -= getWordWidth(word, currentCharIndex);
				int spaceLeft = maxWidth - newWidth;
				if ((word.Length > 3 && spaceLeft > 2 * context.getSpaceWidth())
					|| info.EndElementIndex == startIndex) {
					final ZLTextHyphenationInfo hyphenationInfo = getHyphenationInfo(word);
					int hyphenationPosition = currentCharIndex;
					int subwordWidth = 0;
					for (int right = word.Length - 1, left = currentCharIndex; right > left; ) {
						final int mid = (right + left + 1) / 2;
						int m1 = mid;
						while (m1 > left && !hyphenationInfo.isHyphenationPossible(m1)) {
							--m1;
						}
						if (m1 > left) {
							final int w = getWordWidth(
								word,
								currentCharIndex,
								m1 - currentCharIndex,
								word.Data[word.Offset + m1 - 1] != '-'
							);
							if (w < spaceLeft) {
								left = mid;
								hyphenationPosition = m1;
								subwordWidth = w;
							} else {
								right = mid - 1;
							}
						} else {
							left = mid;
						}
					}
					if (hyphenationPosition == currentCharIndex && info.EndElementIndex == startIndex) {
						subwordWidth = getWordWidth(word, currentCharIndex, 1, false);
						int right = word.Length == currentCharIndex + 1 ? word.Length : word.Length - 1;
						int left = currentCharIndex + 1;
						while (right > left) {
							final int mid = (right + left + 1) / 2;
							final int w = getWordWidth(
								word,
								currentCharIndex,
								mid - currentCharIndex,
								word.Data[word.Offset + mid - 1] != '-'
							);
							if (w <= spaceLeft) {
								left = mid;
								subwordWidth = w;
							} else {
								right = mid - 1;
							}
						}
						hyphenationPosition = right;
					}
					if (hyphenationPosition > currentCharIndex) {
						hyphenated = true;
						info.IsVisible = true;
						info.Width = newWidth + subwordWidth;
						if (info.Height < newHeight) {
							info.Height = newHeight;
						}
						if (info.Descent < newDescent) {
							info.Descent = newDescent;
						}
						info.EndElementIndex = currentElementIndex;
						info.EndCharIndex = hyphenationPosition;
						info.SpaceCounter = internalSpaceCounter;
						storedStyle = getTextStyle();
						removeLastSpace = false;
					}
				}
			}

			if (!hyphenated) {
				for (int i = words.size() - 1; i >= 0; --i) {
					final WordInfo wi = words.get(i);
					final ZLTextWord word = wi.Word;
					if (word.Length <= 3) {
						continue;
					}
					final ZLTextHyphenationInfo hyphenationInfo = getHyphenationInfo(word);
					int pos = word.Length - 1;
					for (; pos > wi.StartCharIndex; --pos) {
						if (hyphenationInfo.isHyphenationPossible(pos)) {
							break;
						}
					}
					if (pos > wi.StartCharIndex) {
						final int subwordWidth = getWordWidth(
							word,
							wi.StartCharIndex,
							pos - wi.StartCharIndex,
							word.Data[word.Offset + pos - 1] != '-'
						);
						info.IsVisible = true;
						info.Width =
							wi.Width - getWordWidth(word, wi.StartCharIndex) + subwordWidth;
						if (info.Height < wi.Height) {
							info.Height = wi.Height;
						}
						if (info.Descent < wi.Descent) {
							info.Descent = wi.Descent;
						}
						info.EndElementIndex = wi.ElementIndex;
						info.EndCharIndex = pos;
						info.SpaceCounter = wi.SpaceCounter;
						storedStyle = wi.Style;
						removeLastSpace = false;
						break;
					}
				}
			}
		}

		if (removeLastSpace) {
			info.Width -= lastSpaceWidth;
			info.SpaceCounter--;
		}

		setTextStyle(storedStyle);

		if (isFirstLine) {
			info.VSpaceBefore = info.StartStyle.getSpaceBefore(metrics());
			if (previousInfo != null) {
				info.PreviousInfoUsed = true;
				info.Height += Math.max(0, info.VSpaceBefore - previousInfo.VSpaceAfter);
			} else {
				info.PreviousInfoUsed = false;
				info.Height += info.VSpaceBefore;
			}
		}
		if (info.isEndOfParagraph()) {
			info.VSpaceAfter = getTextStyle().getSpaceAfter(metrics());
		}

		if (info.EndElementIndex != endIndex || endIndex == info.ParagraphCursorLength) {
			myLineInfoCache.put(info, info);
		}

		return info;
	}

	private void prepareTextLine(ZLTextPage page, ZLTextLineInfo info, int x, int y, int columnIndex) {
		y = Math.min(y + info.Height, getTopMargin() + page.getTextHeight() - 1);

		final ZLPaintContext context = getContext();
		final ZLTextParagraphCursor paragraphCursor = info.ParagraphCursor;

		setTextStyle(info.StartStyle);
		int spaceCounter = info.SpaceCounter;
		int fullCorrection = 0;
		final boolean endOfParagraph = info.isEndOfParagraph();
		boolean wordOccurred = false;
		boolean changeStyle = true;
		x += info.LeftIndent;

		final int maxWidth = page.getTextWidth();
		switch (getTextStyle().getAlignment()) {
			case ZLTextAlignmentType.ALIGN_RIGHT:
				x += maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width;
				break;
			case ZLTextAlignmentType.ALIGN_CENTER:
				x += (maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width) / 2;
				break;
			case ZLTextAlignmentType.ALIGN_JUSTIFY:
				if (!endOfParagraph && (paragraphCursor.getElement(info.EndElementIndex) != ZLTextElement.AfterParagraph)) {
					fullCorrection = maxWidth - getTextStyle().getRightIndent(metrics()) - info.Width;
				}
				break;
			case ZLTextAlignmentType.ALIGN_LEFT:
			case ZLTextAlignmentType.ALIGN_UNDEFINED:
				break;
		}

		final ZLTextParagraphCursor paragraph = info.ParagraphCursor;
		final int paragraphIndex = paragraph.Index;
		final int endElementIndex = info.EndElementIndex;
		int charIndex = info.RealStartCharIndex;
		ZLTextElementArea spaceElement = null;
		for (int wordIndex = info.RealStartElementIndex; wordIndex != endElementIndex; ++wordIndex, charIndex = 0) {
			final ZLTextElement element = paragraph.getElement(wordIndex);
			final int width = getElementWidth(element, charIndex);
			if (element == ZLTextElement.HSpace || element == ZLTextElement.NBSpace) {
				if (wordOccurred && spaceCounter > 0) {
					final int correction = fullCorrection / spaceCounter;
					final int spaceLength = context.getSpaceWidth() + correction;
					if (getTextStyle().isUnderline()) {
						spaceElement = new ZLTextElementArea(
							paragraphIndex, wordIndex, 0,
							0, // length
							true, // is last in element
							false, // add hyphenation sign
							false, // changed style
							getTextStyle(), element, x, x + spaceLength, y, y, columnIndex
						);
					} else {
						spaceElement = null;
					}
					x += spaceLength;
					fullCorrection -= correction;
					wordOccurred = false;
					--spaceCounter;
				}
			} else if (element instanceof ZLTextWord || element instanceof ZLTextImageElement || element instanceof ZLTextVideoElement || element instanceof ExtensionElement) {
				final int height = getElementHeight(element);
				final int descent = getElementDescent(element);
				final int length = element instanceof ZLTextWord ? ((ZLTextWord)element).Length : 0;
				if (spaceElement != null) {
					page.TextElementMap.add(spaceElement);
					spaceElement = null;
				}
				page.TextElementMap.add(new ZLTextElementArea(
					paragraphIndex, wordIndex, charIndex,
					length - charIndex,
					true, // is last in element
					false, // add hyphenation sign
					changeStyle, getTextStyle(), element,
					x, x + width - 1, y - height + 1, y + descent, columnIndex
				));
				changeStyle = false;
				wordOccurred = true;
			} else if (isStyleChangeElement(element)) {
				applyStyleChangeElement(element);
				changeStyle = true;
			}
			x += width;
		}
		if (!endOfParagraph) {
			final int len = info.EndCharIndex;
			if (len > 0) {
				final int wordIndex = info.EndElementIndex;
				final ZLTextWord word = (ZLTextWord)paragraph.getElement(wordIndex);
				final boolean addHyphenationSign = word.Data[word.Offset + len - 1] != '-';
				final int width = getWordWidth(word, 0, len, addHyphenationSign);
				final int height = getElementHeight(word);
				final int descent = context.getDescent();
				page.TextElementMap.add(
					new ZLTextElementArea(
						paragraphIndex, wordIndex, 0,
						len,
						false, // is last in element
						addHyphenationSign,
						changeStyle, getTextStyle(), word,
						x, x + width - 1, y - height + 1, y + descent, columnIndex
					)
				);
			}
		}
	}

	public synchronized final void turnPage(boolean forward, int scrollingMode, int value) {
		preparePaintInfo(myCurrentPage);
		myPreviousPage.reset();
		myNextPage.reset();
		if (myCurrentPage.PaintState == PaintStateEnum.READY) {
			myCurrentPage.PaintState = forward ? PaintStateEnum.TO_SCROLL_FORWARD : PaintStateEnum.TO_SCROLL_BACKWARD;
			myScrollingMode = scrollingMode;
			myOverlappingValue = value;
		}
	}

	public final synchronized void gotoPosition(ZLTextPosition position) {
		if (position != null) {
			gotoPosition(position.getParagraphIndex(), position.getElementIndex(), position.getCharIndex());
		}
	}

	public final synchronized void gotoPosition(int paragraphIndex, int wordIndex, int charIndex) {
		if (myModel != null && myModel.getParagraphsNumber() > 0) {
			Application.getViewWidget().reset();
			myCurrentPage.moveStartCursor(paragraphIndex, wordIndex, charIndex);
			myPreviousPage.reset();
			myNextPage.reset();
			preparePaintInfo(myCurrentPage);
			if (myCurrentPage.isEmptyPage()) {
				turnPage(true, ScrollingMode.NO_OVERLAPPING, 0);
			}
		}
	}

	private final synchronized void gotoPositionByEnd(int paragraphIndex, int wordIndex, int charIndex) {
		if (myModel != null && myModel.getParagraphsNumber() > 0) {
			myCurrentPage.moveEndCursor(paragraphIndex, wordIndex, charIndex);
			myPreviousPage.reset();
			myNextPage.reset();
			preparePaintInfo(myCurrentPage);
			if (myCurrentPage.isEmptyPage()) {
				turnPage(false, ScrollingMode.NO_OVERLAPPING, 0);
			}
		}
	}

	protected synchronized void preparePaintInfo() {
		myPreviousPage.reset();
		myNextPage.reset();
		preparePaintInfo(myCurrentPage);
	}

	private synchronized void preparePaintInfo(ZLTextPage page) {
		preparePaintInfo(page, twoColumnView(), page == myPreviousPage);
	}

	private synchronized void preparePaintInfo(ZLTextPage page, boolean twoColumns, boolean keepEndNotStart) {
		page.setSize(getTextColumnWidth(), getTextAreaHeight(), twoColumns, keepEndNotStart);

		if (page.PaintState == PaintStateEnum.NOTHING_TO_PAINT || page.PaintState == PaintStateEnum.READY) {
			return;
		}
		final int oldState = page.PaintState;

		final HashMap<ZLTextLineInfo,ZLTextLineInfo> cache = myLineInfoCache;
		for (ZLTextLineInfo info : page.LineInfos) {
			cache.put(info, info);
		}

		switch (page.PaintState) {
			default:
				break;
			case PaintStateEnum.TO_SCROLL_FORWARD:
				if (!page.EndCursor.isEndOfText()) {
					final ZLTextWordCursor startCursor = new ZLTextWordCursor();
					switch (myScrollingMode) {
						case ScrollingMode.NO_OVERLAPPING:
							break;
						case ScrollingMode.KEEP_LINES:
							page.findLineFromEnd(startCursor, myOverlappingValue);
							break;
						case ScrollingMode.SCROLL_LINES:
							page.findLineFromStart(startCursor, myOverlappingValue);
							if (startCursor.isEndOfParagraph()) {
								startCursor.nextParagraph();
							}
							break;
						case ScrollingMode.SCROLL_PERCENTAGE:
							page.findPercentFromStart(startCursor, myOverlappingValue);
							break;
					}

					if (!startCursor.isNull() && startCursor.samePositionAs(page.StartCursor)) {
						page.findLineFromStart(startCursor, 1);
					}

					if (!startCursor.isNull()) {
						final ZLTextWordCursor endCursor = new ZLTextWordCursor();
						buildInfos(page, startCursor, endCursor);
						if (!page.isEmptyPage() && (myScrollingMode != ScrollingMode.KEEP_LINES || !endCursor.samePositionAs(page.EndCursor))) {
							page.StartCursor.setCursor(startCursor);
							page.EndCursor.setCursor(endCursor);
							break;
						}
					}

					page.StartCursor.setCursor(page.EndCursor);
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
			case PaintStateEnum.TO_SCROLL_BACKWARD:
				if (!page.StartCursor.isStartOfText()) {
					switch (myScrollingMode) {
						case ScrollingMode.NO_OVERLAPPING:
							page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
							break;
						case ScrollingMode.KEEP_LINES:
						{
							ZLTextWordCursor endCursor = new ZLTextWordCursor();
							page.findLineFromStart(endCursor, myOverlappingValue);
							if (!endCursor.isNull() && endCursor.samePositionAs(page.EndCursor)) {
								page.findLineFromEnd(endCursor, 1);
							}
							if (!endCursor.isNull()) {
								ZLTextWordCursor startCursor = findStartOfPrevousPage(page, endCursor);
								if (startCursor.samePositionAs(page.StartCursor)) {
									page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
								} else {
									page.StartCursor.setCursor(startCursor);
								}
							} else {
								page.StartCursor.setCursor(findStartOfPrevousPage(page, page.StartCursor));
							}
							break;
						}
						case ScrollingMode.SCROLL_LINES:
							page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.LINE_UNIT, myOverlappingValue));
							break;
						case ScrollingMode.SCROLL_PERCENTAGE:
							page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.PIXEL_UNIT, page.getTextHeight() * myOverlappingValue / 100));
							break;
					}
					buildInfos(page, page.StartCursor, page.EndCursor);
					if (page.isEmptyPage()) {
						page.StartCursor.setCursor(findStart(page, page.StartCursor, SizeUnit.LINE_UNIT, 1));
						buildInfos(page, page.StartCursor, page.EndCursor);
					}
				}
				break;
			case PaintStateEnum.START_IS_KNOWN:
				if (!page.StartCursor.isNull()) {
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
			case PaintStateEnum.END_IS_KNOWN:
				if (!page.EndCursor.isNull()) {
					page.StartCursor.setCursor(findStartOfPrevousPage(page, page.EndCursor));
					buildInfos(page, page.StartCursor, page.EndCursor);
				}
				break;
		}
		page.PaintState = PaintStateEnum.READY;
		// TODO: cache?
		myLineInfoCache.clear();

		if (page == myCurrentPage) {
			if (oldState != PaintStateEnum.START_IS_KNOWN) {
				myPreviousPage.reset();
			}
			if (oldState != PaintStateEnum.END_IS_KNOWN) {
				myNextPage.reset();
			}
		}
	}

	public void clearCaches() {
		resetMetrics();
		rebuildPaintInfo();
		Application.getViewWidget().reset();
		myStartPages.clear();
		myEndPages.clear();
	}

	protected synchronized void rebuildPaintInfo() {
		myPreviousPage.reset();
		myNextPage.reset();
		if (myCursorManager != null) {
			myCursorManager.evictAll();
		}

		if (myCurrentPage.PaintState != PaintStateEnum.NOTHING_TO_PAINT) {
			myCurrentPage.LineInfos.clear();
			if (!myCurrentPage.StartCursor.isNull()) {
				myCurrentPage.StartCursor.rebuild();
				myCurrentPage.EndCursor.reset();
				myCurrentPage.PaintState = PaintStateEnum.START_IS_KNOWN;
			} else if (!myCurrentPage.EndCursor.isNull()) {
				myCurrentPage.EndCursor.rebuild();
				myCurrentPage.StartCursor.reset();
				myCurrentPage.PaintState = PaintStateEnum.END_IS_KNOWN;
			}
		}

		myLineInfoCache.clear();
	}

	private int infoSize(ZLTextLineInfo info, int unit) {
		return (unit == SizeUnit.PIXEL_UNIT) ? (info.Height + info.Descent + info.VSpaceAfter) : (info.IsVisible ? 1 : 0);
	}

	private static class ParagraphSize {
		public int Height;
		public int TopMargin;
		public int BottomMargin;
	}

	private ParagraphSize paragraphSize(ZLTextPage page, ZLTextWordCursor cursor, boolean beforeCurrentPosition, int unit) {
		final ParagraphSize size = new ParagraphSize();

		final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return size;
		}
		final int endElementIndex =
			beforeCurrentPosition ? cursor.getElementIndex() : paragraphCursor.getParagraphLength();

		resetTextStyle();

		int wordIndex = 0;
		int charIndex = 0;
		ZLTextLineInfo info = null;
		while (wordIndex != endElementIndex) {
			final ZLTextLineInfo prev = info;
			info = processTextLine(page, paragraphCursor, wordIndex, charIndex, endElementIndex, prev);
			wordIndex = info.EndElementIndex;
			charIndex = info.EndCharIndex;
			size.Height += infoSize(info, unit);
			if (prev == null) {
				size.TopMargin = info.VSpaceBefore;
			}
			size.BottomMargin = info.VSpaceAfter;
		}

		return size;
	}

	private void skip(ZLTextPage page, ZLTextWordCursor cursor, int unit, int size) {
		final ZLTextParagraphCursor paragraphCursor = cursor.getParagraphCursor();
		if (paragraphCursor == null) {
			return;
		}
		final int endElementIndex = paragraphCursor.getParagraphLength();

		resetTextStyle();
		applyStyleChanges(paragraphCursor, 0, cursor.getElementIndex());

		ZLTextLineInfo info = null;
		while (!cursor.isEndOfParagraph() && size > 0) {
			info = processTextLine(page, paragraphCursor, cursor.getElementIndex(), cursor.getCharIndex(), endElementIndex, info);
			cursor.moveTo(info.EndElementIndex, info.EndCharIndex);
			size -= infoSize(info, unit);
		}
	}

	private ZLTextWordCursor findStartOfPrevousPage(ZLTextPage page, ZLTextWordCursor end) {
		if (page.twoColumnView()) {
			end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
		}
		end = findStart(page, end, SizeUnit.PIXEL_UNIT, page.getTextHeight());
		return end;
	}

	private ZLTextWordCursor findStart(ZLTextPage page, ZLTextWordCursor end, int unit, int height) {
		final ZLTextWordCursor start = new ZLTextWordCursor(end);
		ParagraphSize size = paragraphSize(page, start, true, unit);
		height -= size.Height;
		boolean positionChanged = !start.isStartOfParagraph();
		start.moveToParagraphStart();
		while (height > 0) {
			final ParagraphSize previousSize = size;
			if (positionChanged && start.getParagraphCursor().isEndOfSection()) {
				break;
			}
			if (!start.previousParagraph()) {
				break;
			}
			if (!start.getParagraphCursor().isEndOfSection()) {
				positionChanged = true;
			}
			size = paragraphSize(page, start, false, unit);
			height -= size.Height;
			if (previousSize != null) {
				height += Math.min(size.BottomMargin, previousSize.TopMargin);
			}
		}
		skip(page, start, unit, -height);

		if (unit == SizeUnit.PIXEL_UNIT) {
			boolean sameStart = start.samePositionAs(end);
			if (!sameStart && start.isEndOfParagraph() && end.isStartOfParagraph()) {
				ZLTextWordCursor startCopy = new ZLTextWordCursor(start);
				startCopy.nextParagraph();
				sameStart = startCopy.samePositionAs(end);
			}
			if (sameStart) {
				start.setCursor(findStart(page, end, SizeUnit.LINE_UNIT, 1));
			}
		}

		return start;
	}

	protected ZLTextElementArea getElementByCoordinates(int x, int y) {
		return myCurrentPage.TextElementMap.binarySearch(x, y);
	}

	public final void outlineRegion(ZLTextRegion region) {
		outlineRegion(region != null ? region.getSoul() : null);
	}

	public final void outlineRegion(ZLTextRegion.Soul soul) {
		myShowOutline = true;
		myOutlinedRegionSoul = soul;
	}

	public void hideOutline() {
		myShowOutline = false;
		Application.getViewWidget().reset();
	}

	private ZLTextRegion getOutlinedRegion(ZLTextPage page) {
		return page.TextElementMap.getRegion(myOutlinedRegionSoul);
	}

	public ZLTextRegion getOutlinedRegion() {
		return getOutlinedRegion(myCurrentPage);
	}

/*
	public void resetRegionPointer() {
		myOutlinedRegionSoul = null;
		myShowOutline = true;
	}
*/

	protected ZLTextHighlighting findHighlighting(int x, int y, int maxDistance) {
		final ZLTextRegion region = findRegion(x, y, maxDistance, ZLTextRegion.AnyRegionFilter);
		if (region == null) {
			return null;
		}
		synchronized (myHighlightings) {
			for (ZLTextHighlighting h : myHighlightings) {
				if (h.getBackgroundColor() != null && h.intersects(region)) {
					return h;
				}
			}
		}
		return null;
	}

	protected ZLTextRegion findRegion(int x, int y, ZLTextRegion.Filter filter) {
		return findRegion(x, y, Integer.MAX_VALUE - 1, filter);
	}

	protected ZLTextRegion findRegion(int x, int y, int maxDistance, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.findRegion(x, y, maxDistance, filter);
	}

	protected ZLTextElementAreaVector.RegionPair findRegionsPair(int x, int y, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.findRegionsPair(x, y, getColumnIndex(x), filter);
	}

	protected boolean initSelection(int x, int y) {
		y -= getTextStyleCollection().getBaseStyle().getFontSize() / 2;
		if (!mySelection.start(x, y)) {
			return false;
		}
		Application.getViewWidget().reset();
		Application.getViewWidget().repaint();
		return true;
	}

	public void clearSelection() {
		if (mySelection.clear()) {
			Application.getViewWidget().reset();
			Application.getViewWidget().repaint();
		}
	}

	public ZLTextHighlighting getSelectionHighlighting() {
		return mySelection;
	}

	public int getSelectionStartY() {
		if (mySelection.isEmpty()) {
			return 0;
		}
		final ZLTextElementArea selectionStartArea = mySelection.getStartArea(myCurrentPage);
		if (selectionStartArea != null) {
			return selectionStartArea.YStart;
		}
		if (mySelection.hasPartBeforePage(myCurrentPage)) {
			final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
			return firstArea != null ? firstArea.YStart : 0;
		} else {
			final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
			return lastArea != null ? lastArea.YEnd : 0;
		}
	}

	public int getSelectionEndY() {
		if (mySelection.isEmpty()) {
			return 0;
		}
		final ZLTextElementArea selectionEndArea = mySelection.getEndArea(myCurrentPage);
		if (selectionEndArea != null) {
			return selectionEndArea.YEnd;
		}
		if (mySelection.hasPartAfterPage(myCurrentPage)) {
			final ZLTextElementArea lastArea = myCurrentPage.TextElementMap.getLastArea();
			return lastArea != null ? lastArea.YEnd : 0;
		} else {
			final ZLTextElementArea firstArea = myCurrentPage.TextElementMap.getFirstArea();
			return firstArea != null ? firstArea.YStart : 0;
		}
	}

	public ZLTextPosition getSelectionStartPosition() {
		return mySelection.getStartPosition();
	}

	public ZLTextPosition getSelectionEndPosition() {
		return mySelection.getEndPosition();
	}

	public boolean isSelectionEmpty() {
		return mySelection.isEmpty();
	}

	public ZLTextRegion nextRegion(Direction direction, ZLTextRegion.Filter filter) {
		return myCurrentPage.TextElementMap.nextRegion(getOutlinedRegion(), direction, filter);
	}

	@Override
	public boolean canScroll(PageIndex index) {
		switch (index) {
			default:
				return true;
			case next:
			{
				final ZLTextWordCursor cursor = getEndCursor();
				return cursor != null && !cursor.isNull() && !cursor.isEndOfText();
			}
			case previous:
			{
				final ZLTextWordCursor cursor = getStartCursor();
				return cursor != null && !cursor.isNull() && !cursor.isStartOfText();
			}
		}
	}

	ZLTextParagraphCursor cursor(int index) {
		return myCursorManager.get(index);
	}

	protected abstract ExtensionElementManager getExtensionManager();
}
