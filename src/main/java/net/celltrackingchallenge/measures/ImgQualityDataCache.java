/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2017, Vladimír Ulman
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.celltrackingchallenge.measures;

import net.celltrackingchallenge.measures.util.MutualFgDistances;
import net.imagej.mesh.Mesh;
import net.imagej.mesh.Vertices;
import net.imagej.ops.OpService;
import net.imglib2.AbstractInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.roi.geom.real.DefaultWritablePolygon2D;
import net.imglib2.roi.geom.real.Polygon2D;
import net.imglib2.type.logic.BitType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.log.Logger;

import net.imglib2.img.Img;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.RealType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import io.scif.img.ImgIOException;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Vector;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Collection;

public class ImgQualityDataCache
{
	///shortcuts to some Fiji services
	private Logger log;
	OpService ops;

	/**
	 * flag to notify ClassifyLabels() if to call extractObjectDistance()
	 * (which will be called in addition to extractFGObjectStats())
	 */
	public boolean doDensityPrecalculation = false;
	///flag to notify extractFGObjectStats() if to bother itself with surface mesh
	public boolean doShapePrecalculation = false;

	///specifies how many digits are to be expected in the input filenames
	public int noOfDigits = 3;

	///a constructor requiring connection to Fiji report/log services
	public ImgQualityDataCache(final Logger _log, final OpService _ops)
	{
		//check that non-null was given for _log!
		if (_log == null)
			throw new NullPointerException("No log service supplied.");
		log = _log;

		if (_ops == null)
			log.warn("OpService not provided, some measures may not be functional...");
		ops = _ops;
	}

	/**
	 * a constructor requiring connection to Fiji report/log services;
	 * this constructor preserves demanded feature flags as they are
	 * given in the foreign \e _cache; \e _cache can be null and then
	 * nothing is preserved
	 */
	public ImgQualityDataCache(final Logger _log, final ImgQualityDataCache _cache)
	{
		this(_log, _cache != null ? _cache.ops : null);
		//NB: reuse ops from the given _cache, if there is some...

		if (_cache != null)
		{
			//preserve the feature flags
			doDensityPrecalculation = _cache.doDensityPrecalculation;
			doShapePrecalculation   = _cache.doShapePrecalculation;
			noOfDigits = _cache.noOfDigits;
		}
		else
		{
			log.warn("Couldn't provide OpService because no cache was given.");
		}
	}

	public
	void provideOpService(final OpService _ops)
	{
		ops = _ops;
	}

	///GT and RES paths combination for which this cache is valid, null means invalid
	private String imgPath = null;
	///GT and RES paths combination for which this cache is valid, null means invalid
	private String annPath = null;

	///reference-based-only check if the parameters are those on which this cache was computed
	public boolean validFor(final String _imgPath, final String _annPath)
	{
		return ( imgPath != null &&  annPath != null
		     && _imgPath != null && _annPath != null
		     && imgPath == _imgPath //intentional match on reference (and on the content)
		     && annPath == _annPath);
	}


	// ----------- the common upper stage essentially starts here -----------
	//auxiliary data:

	///representation of resolution, no dimensionality restriction (unlike in GUI)
	private double[] resolution = null;

	public void setResolution(final double[] _res)
	{
		//check if resolution data is sane
		if (_res == null)
			throw new IllegalArgumentException("No pixel resolution data supplied!");
		for (double r : _res)
			if (r <= 0.0)
				throw new IllegalArgumentException("Negative or zero resolution supplied!");

		//copy the supplied resolution to the class structures,
		resolution = new double[_res.length];
		for (int n=0; n < resolution.length; ++n)
			resolution[n] = _res[n];
	}

	/**
	 * This class holds all relevant data that are a) needed for individual
	 * measures to carry on their calculations and b) that are shared between
	 * these measures (so there is no need to scan the raw images all over again
	 * and again, once per every measure) and c) that are valid for one video
	 * (see the this.cachedVideoData).
	 */
	public class videoDataContainer
	{
		public videoDataContainer(final String __imgPath, final int __v)
		{
			video = __v;

			videoTable = new HashMap<>(3000); //keys are timepoints
			videoNameStr = Integer.valueOf(__v).toString();
			//
			//extract dataset name
			final int idx = __imgPath.lastIndexOf('/');
			datasetNameStr = idx > -1 ? __imgPath.substring(idx+1) : __imgPath;
		}

		///number/ID of the video this data belongs to
		public int video;

		/**
		 * Representation of average & std. deviations within individual
		 * foreground masks.
		 * Usage: avgFG[timePoint].get(labelID) = averageIntensityValue
		 */
		public final Vector<HashMap<Integer,Double>> avgFG = new Vector<>(1000,100);
		/// Similar to this.avgFG
		public final Vector<HashMap<Integer,Double>> stdFG = new Vector<>(1000,100);

		/// Stores NUMBER OF VOXELS (not a real volume) of the FG masks at time points.
		public final Vector<HashMap<Integer,Long>> volumeFG = new Vector<>(1000,100);

		/// Converts this.volumeFG values (no. of voxels) into a real volume (in cubic micrometers)
		public double getRealVolume(final long vxlCnt)
		{
			double v = (double)vxlCnt;
			for (double r : resolution) v *= r;
			return (v);
		}

		/// Stores the circularity (for 2D data) or sphericity (for 3D data)
		public final Vector<HashMap<Integer,Double>> shaValuesFG = new Vector<>(1000,100);

		/**
		 * Stores how many voxels are there in the intersection of masks of the same
		 * marker at time point and previous time point.
		 */
		public final Vector<HashMap<Integer,Long>> overlapFG = new Vector<>(1000,100);

		/**
		 * Stores how many voxels are there in between the marker and its nearest
		 * neighboring (other) marker at time points. The distance is measured with
		 * Chamfer distance (which considers diagonals in voxels) and thus the value
		 * is not necessarily an integer anymore. The resolution (size of voxels)
		 * of the image is not taken into account.
		 */
		public final Vector<HashMap<Integer,Float>> nearDistFG = new Vector<>(1000,100);

		/**
		 * Stores axis-aligned, 3D bounding box around every discovered FG marker (per each timepoint,
		 * just like it is the case with most of the attributes around). Pixel coordinates are used.
		 */
		public final Vector<Map<Integer,int[]>> boundingBoxesFG = new Vector<>(1000,100);

		/**
		 * Representation of average & std. deviations of background region.
		 * There is only one background marker expected in the images.
		 */
		public final Vector<Double> avgBG = new Vector<>(1000,100);
		/// Similar to this.avgBG
		public final Vector<Double> stdBG = new Vector<>(1000,100);

		final Map<Integer, Map<Integer,MeasuresTableRow>> videoTable;
		final String datasetNameStr, videoNameStr;
		//
		public MeasuresTableRow getTableRowFor(final int timepoint, final int cellID)
		{
			videoTable.putIfAbsent(timepoint, new HashMap<>(5000));
			final Map<Integer,MeasuresTableRow> tableAtTime = videoTable.get(timepoint);

			MeasuresTableRow row = tableAtTime.getOrDefault(cellID, null);
			if (row == null) {
				row = new MeasuresTableRow(datasetNameStr, videoNameStr, timepoint, cellID);
				tableAtTime.put(cellID, row);
			}

			return row;
		}
	}

	public static class MeasuresTableRow
	{
		public MeasuresTableRow(
				final String datasetName, final String videoSequence,
				final int timePoint, final int cellTraId)
		{
			this.datasetName = datasetName;
			this.videoSequence = videoSequence;
			this.timePoint = timePoint;
			this.cellTraId = cellTraId;
		}

		//row key-identifier
		public final String datasetName;
		public final String videoSequence;
		public final int timePoint;
		public final int cellTraId;

		//row data
		public double snr, cr, heti, hetb, res, sha, spa, cha, ove, mit;

		final static String sep = "; ";

		public static String printHeader() {
			return "# datasetName" + sep
					+ "videoSequence" + sep
					+ "timePoint" + sep
					+ "cellTraId" + sep
					+ "snr" + sep
					+ "cr" + sep
					+ "heti" + sep
					+ "hetb" + sep
					+ "res" + sep
					+ "sha" + sep
					+ "spa" + sep
					+ "cha" + sep
					+ "ove" + sep
					+ "mit";
		}

		@Override
		public String toString() {
			return datasetName + sep
					+ videoSequence + sep
					+ timePoint + sep
					+ cellTraId + sep
					+ snr + sep
					+ cr + sep
					+ heti + sep
					+ hetb + sep
					+ res + sep
					+ sha + sep
					+ spa + sep
					+ cha + sep
					+ ove + sep
					+ mit;
		}
	}

	/// this list holds relevant data for every discovered video
	List<videoDataContainer> cachedVideoData = new LinkedList<>();

	public Collection<MeasuresTableRow> getMeasuresTable()
	{
		//estimated how many rows will the table have
		int noOfTableLines = 0;
		for (videoDataContainer video : cachedVideoData) {
			for (Map<Integer,MeasuresTableRow> timepoint : video.videoTable.values()) {
				noOfTableLines += timepoint.size();
			}
		}

		final List<MeasuresTableRow> concatenatedTable = new ArrayList<>(noOfTableLines);
		for (videoDataContainer video : cachedVideoData) {
			for (Map<Integer,MeasuresTableRow> timepoint : video.videoTable.values()) {
				concatenatedTable.addAll(timepoint.values());
			}
		}

		return concatenatedTable;
	}

	public Collection<MeasuresTableRow> getMeasuresTable_GroupedByCellsThenByVideos()
	{
		//estimated how many rows will the table have
		int noOfTableLines = 0;
		for (videoDataContainer video : cachedVideoData) {
			for (Map<Integer,MeasuresTableRow> timepoint : video.videoTable.values()) {
				noOfTableLines += timepoint.size();
			}
		}

		final List<MeasuresTableRow> concatenatedTable = new ArrayList<>(noOfTableLines);
		for (videoDataContainer video : cachedVideoData) {
			Set<Integer> discoveredIds = new HashSet<>(3000);
			for (Map<Integer,MeasuresTableRow> timepoint : video.videoTable.values())
				discoveredIds.addAll(timepoint.keySet());

			for (int cellId : discoveredIds) {
				for (Map<Integer,MeasuresTableRow> timepoint : video.videoTable.values()) {
					if (timepoint.containsKey(cellId))
						concatenatedTable.add(timepoint.get(cellId));
				}
			}
		}

		return concatenatedTable;
	}

	//---------------------------------------------------------------------/
	//aux data fillers -- merely markers' properties calculator

	/**
	 * The cursor \e imgPosition points into the raw image at voxel whose counterparting voxel
	 * in the \e imgFGcurrent image stores the first (in the sense of \e imgPosition internal
	 * sweeping order) occurence of the marker that is to be processed with this function.
	 *
	 * This function pushes into global data at the specific \e time .
	 */
	private <T extends RealType<T>>
	void extractFGObjectStats(final int marker,
		final IterableInterval<T> iRaw,
		final RandomAccessibleInterval<UnsignedShortType> iFgCurr,     //where: input masks
		final RandomAccessibleInterval<UnsignedShortType> iFgPrev,
		final videoDataContainer data, final int time)
	{
		//working pointers into the mask images
		final Cursor<T> rawCursor = iRaw.localizingCursor();
		final RandomAccess<UnsignedShortType> fgCursor = iFgCurr.randomAccess();

		//advance until over 'marker' object/pixel
		while (rawCursor.hasNext()) {
			rawCursor.next();
			if (fgCursor.setPositionAndGet(rawCursor).getInteger() == marker) break;
		}
		if (fgCursor.get().getInteger() != marker)
			throw new RuntimeException("Inconsistency when seeking marker "+marker);
		//NB: the first voxel with 'marker' is now discovered; since we will
		//    continue sweeping the image from the next voxel, we will already
		//    count-in this current voxel -> thus, vxlCnt = 1

		//init aux variables:
		double intSum = 0.; //for single-pass calculation of mean and variance
		double int2Sum = 0.;
		//according to: https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Computing_shifted_data
		//to fight against numerical issues we introduce a "value shifter",
		//which we can already initiate with an "estimate of mean" which we
		//derive from the object's first spotted voxel value
		//NB: rawCursor points already at sure existing voxel
		final double valShift=rawCursor.get().getRealDouble();

		//the voxel counter (for volume)
		long vxlCnt = 1L;

		//continue sweeping the image while searching further for this object/marker
		while (rawCursor.hasNext())
		{
			rawCursor.next();
			fgCursor.setPosition(rawCursor);

			if (fgCursor.get().getInteger() == marker)
			{
				//processing voxel that belongs to the current FG object:
				//increase current volume
				++vxlCnt;

				final double val = rawCursor.get().getRealDouble();
				intSum += (val-valShift);
				int2Sum += (val-valShift) * (val-valShift);
			}
		}
		//must hold: vxlCnt > 1 (otherwise ClassifyLabels wouldn't call this function)

		//finish processing of the FG objects stats:
		//mean intensity
		data.avgFG.get(time).put(marker, (intSum / (double)vxlCnt) + valShift );

		//variance
		int2Sum -= (intSum*intSum/(double)vxlCnt);
		int2Sum /= (double)vxlCnt;
		//
		//std. dev.
		data.stdFG.get(time).put(marker, Math.sqrt(int2Sum) );

		//voxel count
		data.volumeFG.get(time).put(marker, vxlCnt );

		//also process the "overlap feature" (if the object was found in the previous frame)
		if (time > 0 && data.volumeFG.get(time-1).get(marker) != null)
			data.overlapFG.get(time).put(marker,
				measureObjectsOverlap(marker,iFgCurr,iFgPrev) );
	}


	private
	double computeSphericity(final int fgValue,
	                         final RandomAccessibleInterval<UnsignedShortType> imgFGcurrent, //FG mask
	                         final RandomAccessibleInterval<BitType> imgTmpSharedStorage)
	{
		if (ops == null)
			throw new RuntimeException("computeSphericity() is missing the Ops service in its context, sorry.");

		//extract the FG mask into separate binary image
		LoopBuilder.setImages(imgFGcurrent, imgTmpSharedStorage)
				.forEachPixel((s, t) -> {
					if (s.getInteger() == fgValue) t.setOne();
					else t.setZero();
				});

		final Mesh m = ops.geom().marchingCubes(imgTmpSharedStorage);

		//apply resolution correction
		final Vertices mv = m.vertices();
		for (int cnt = 0; cnt < mv.size(); ++cnt) {
			mv.setPosition(cnt, resolution[0]*mv.x(cnt), resolution[1]*mv.y(cnt), resolution[2]*mv.z(cnt) );
		}

		log.trace("marker "+fgValue+" volume is "+ops.geom().size(m));
		log.trace("marker "+fgValue+" surface is "+ops.geom().boundarySize(m));
		log.debug("Sphericity of a marker "+fgValue+" is "+ops.geom().sphericity(m).getRealDouble());
		return ops.geom().sphericity(m).getRealDouble();
	}

	private
	double computeCircularity(final int fgValue,
	                          final RandomAccessibleInterval<UnsignedShortType> imgFGcurrent, //FG mask
	                          final RandomAccessibleInterval<BitType> imgTmpSharedStorage)
	{
		if (ops == null)
			throw new RuntimeException("computeCircularity() is missing the Ops service in its context, sorry.");

		//extract the FG mask into separate binary image
		LoopBuilder.setImages(imgFGcurrent, imgTmpSharedStorage)
				.forEachPixel((s, t) -> {
					if (s.getInteger() == fgValue) t.setOne();
					else t.setZero();
				});

		Polygon2D p = ops.geom().contour(imgTmpSharedStorage, true);
		if (p.numDimensions() != 2)
			throw new RuntimeException("computeCircularity() failed extracting 2D polygon, sorry.");

		//apply resolution correction
		if (resolution[0] != 1.0 || resolution[1] != 1.0) {
			log.info("Applying 2D resolution correction for SHA measure: "+ Arrays.toString(resolution));
			final double[] x = new double[p.numVertices()];
			final double[] y = new double[p.numVertices()];
			for (int cnt = 0; cnt < p.numVertices(); ++cnt) {
				x[cnt] = resolution[0] * p.vertices().get(cnt).getDoublePosition(0);
				y[cnt] = resolution[1] * p.vertices().get(cnt).getDoublePosition(1);
			}
			p = new DefaultWritablePolygon2D(x,y);
		}

		log.trace("marker "+fgValue+" area is "+ops.geom().size(p));
		final double perimeter = ops.geom().boundarySize(p).getRealDouble();
		log.trace("marker "+fgValue+" perimeter is "+perimeter);
		log.debug("Circularity of a marker "+fgValue+" is "+ops.geom().circularity(p).getRealDouble());
		if (perimeter < 0.0001) {
			log.info("marker "+fgValue+" PROBLEMATIC SHA, returning fake value of 0.5");
			return 0.5;
		}
		return ops.geom().circularity(p).getRealDouble();
	}


	/**
	 * The functions counts how many times the given \e marker in the image \e imgFGcurrent
	 * co-localizes with the same marker in the image \e imgFGprevious. This number is returned.
	 */
	private
	long measureObjectsOverlap(final int marker,
	                           final RandomAccessibleInterval<UnsignedShortType> imgFGcurrent, //FG mask
	                           final RandomAccessibleInterval<UnsignedShortType> imgFGprevious) //FG mask
	{
		final long[] count = {0L};
		LoopBuilder.setImages(imgFGcurrent,imgFGprevious).forEachPixel( (a,b)
				-> count[0] += a.getInteger() == marker && b.getInteger() == marker ? 1 : 0 );
		return(count[0]);
	}


	private int[] _location = new int[3];
	private void assureArrayLengthFor(final int length)
	{
		if (_location.length != length) _location = new int[length];
	}
	//
	private int[] createBox(final Localizable loc)
	{
		final int D = loc.numDimensions();
		assureArrayLengthFor(D);
		//
		loc.localize(_location);
		final int[] bbox = new int[D+D];
		for (int d = 0; d < D; ++d) {
			bbox[d]   = _location[d];
			bbox[d+D] = _location[d];
		}
		return bbox;
	}
	private void extendBox(final int[] bbox, final Localizable loc)
	{
		final int D = loc.numDimensions();
		assureArrayLengthFor(D);
		//
		loc.localize(_location);
		for (int d = 0; d < D; ++d) {
			bbox[d]   = Math.min(bbox[d],   _location[d]);
			bbox[d+D] = Math.max(bbox[d+D], _location[d]);
		}
	}
	//
	private Interval wrapBoxWithInterval(final int[] bbox)
	{
		if (_interval.numDimensions() * 2 != bbox.length)
			_interval = new BboxBackedInterval(bbox);
		_interval.wrapAroundBbox(bbox);
		return _interval;
	}
	private BboxBackedInterval _interval = new BboxBackedInterval(3);
	static class BboxBackedInterval extends AbstractInterval {
		private BboxBackedInterval(final int n) {
			super(n);
		}
		public BboxBackedInterval(final int[] bbox) {
			super(bbox.length / 2);
		}
		public void wrapAroundBbox(final int[] bbox) {
			final int D = bbox.length / 2;
			for (int d = 0; d < D; ++d) {
				min[d] = bbox[d];
				max[d] = bbox[d+D];
			}
		}
	}

	private boolean isBoxLargeEnoughForSha(final int[] bbox)
	{
		int minL = 2; //size of at least 2px in some axis is considered to be good enough
		final int D = bbox.length / 2;
		for (int d = 0; d < D; ++d)
			minL = Math.min(minL, bbox[d+D]-bbox[d]+1);
		return minL >= 2;
	}


	public <T extends RealType<T>>
	void ClassifyLabels(final int time,
	                    Img<T> imgRaw,
	                    RandomAccessibleInterval<UnsignedByteType> imgBG,
	                    Img<UnsignedShortType> imgFG,
	                    RandomAccessibleInterval<UnsignedShortType> imgFGprev,
	                    final videoDataContainer data)
	{
		//uses resolution from the class internal structures, check it is set already
		if (resolution == null)
			throw new IllegalArgumentException("No pixel resolution data is available!");
		//assume that resolution is sane

		//check we have a resolution data available for every dimension
		if (imgRaw.numDimensions() > resolution.length)
			throw new IllegalArgumentException("Raw image has greater dimensionality"
				+" than the available resolution data.");

		//check the sizes of the images
		if (imgRaw.numDimensions() != imgFG.numDimensions())
			throw new IllegalArgumentException("Raw image and FG label image"
				+" are not of the same dimensionality.");
		if (imgRaw.numDimensions() != imgBG.numDimensions())
			throw new IllegalArgumentException("Raw image and BG label image"
				+" are not of the same dimensionality.");

		for (int n=0; n < imgRaw.numDimensions(); ++n)
			if (imgRaw.dimension(n) != imgFG.dimension(n))
				throw new IllegalArgumentException("Raw image and FG label image"
					+" are not of the same size.");
		for (int n=0; n < imgRaw.numDimensions(); ++n)
			if (imgRaw.dimension(n) != imgBG.dimension(n))
				throw new IllegalArgumentException("Raw image and BG label image"
					+" are not of the same size.");

		//.... populate the internal structures ....
		//first, frame-related stats variables:
		long volBGvoxelCnt = 0L;
		long volFGvoxelCnt = 0L;
		long volFGBGcollisionVoxelCnt = 0L;

		double intSum = 0.; //for mean and variance
		double int2Sum = 0.;
		//see extractFGObjectStats() for explanation of this variable
		double valShift=-1.;

		//bounding boxes
		final Map<Integer,int[]> bboxes = new HashMap<>(1000);
		data.boundingBoxesFG.add(bboxes);

		//sweeping variables:
		final Cursor<T> rawCursor = imgRaw.localizingCursor();
		final RandomAccess<UnsignedByteType> bgCursor = imgBG.randomAccess();
		final RandomAccess<UnsignedShortType> fgCursor = imgFG.randomAccess();

		while (rawCursor.hasNext())
		{
			//update cursors...
			rawCursor.next();
			bgCursor.setPosition(rawCursor);
			fgCursor.setPosition(rawCursor);

			//analyze background voxels
			if (bgCursor.get().getInteger() > 0)
			{
				if (fgCursor.get().getInteger() > 0)
				{
					//found colliding BG voxel, exclude it from BG stats
					++volFGBGcollisionVoxelCnt;
				}
				else
				{
					//found non-colliding BG voxel, include it for BG stats
					++volBGvoxelCnt;

					final double val = rawCursor.get().getRealDouble();
					if (valShift == -1) valShift = val;

					intSum += (val-valShift);
					int2Sum += (val-valShift) * (val-valShift);
				}
			}
			if (fgCursor.get().getInteger() > 0)
			{
				++volFGvoxelCnt; //found FG voxel, update FG stats
				bboxes.putIfAbsent(fgCursor.get().getInteger(), createBox(rawCursor));
				extendBox(bboxes.get(fgCursor.get().getInteger()), rawCursor);
			}
		}

		//report the "occupancy stats"
		log.info("Frame at time "+time+" overview:");
		final long imgSize = imgRaw.size();
		log.info("all FG voxels           : "+volFGvoxelCnt+" ( "+100.0*(double)volFGvoxelCnt/imgSize+" %)");
		log.info("pure BG voxels          : "+volBGvoxelCnt+" ( "+100.0*(double)volBGvoxelCnt/imgSize+" %)");
		log.info("BG&FG overlapping voxels: "+volFGBGcollisionVoxelCnt+" ( "+100.0*(double)volFGBGcollisionVoxelCnt/imgSize+" %)");
		final long untouched = imgSize - volFGvoxelCnt - volBGvoxelCnt;
		log.info("not annotated voxels    : "+untouched+" ( "+100.0*(double)untouched/imgSize+" %)");
		//
		for (int marker : bboxes.keySet())
			log.trace("bbox for marker "+marker+": "+ Arrays.toString(bboxes.get(marker)));

		//finish processing of the BG stats of the current frame
		if (volBGvoxelCnt > 0)
		{
			//great, some pure-background voxels have been found
			data.avgBG.add( (intSum / (double)volBGvoxelCnt) + valShift );

			int2Sum -= (intSum*intSum/(double)volBGvoxelCnt);
			int2Sum /= (double)volBGvoxelCnt;
			data.stdBG.add( Math.sqrt(int2Sum) );
		}
		else
		{
			log.info("Warning: Background annotation has no pure background voxels.");
			data.avgBG.add( 0.0 );
			data.stdBG.add( 0.0 );
		}

		//now, sweep the image, detect all labels and calculate & save their properties
		log.info("Retrieving per object statistics, might take some time...");

		//prepare the per-object data structures
		data.avgFG.add( new HashMap<>() );
		data.stdFG.add( new HashMap<>() );
		data.volumeFG.add( new HashMap<>() );
		data.shaValuesFG.add( new HashMap<>() );
		data.overlapFG.add( new HashMap<>() );
		data.nearDistFG.add( new HashMap<>() );

		final MutualFgDistances fgDists = new MutualFgDistances(imgFG.numDimensions());
		if (doDensityPrecalculation && bboxes.size() > 1)
		{
			//if there are at least two markers (and thus measuring density does make sense at all),
			//do get all boundary pixels then...
			for (int marker : bboxes.keySet())
			{
				log.trace("Discovering surface for a marker "+marker);
				fgDists.findAndSaveSurface( marker, imgFG,
						wrapBoxWithInterval(bboxes.get(marker)) );
			}

			//fill the distance matrix
			for (int markerA : bboxes.keySet())
				for (int markerB : bboxes.keySet())
					if (markerA != markerB && fgDists.getDistance(markerA,markerB) == Float.MAX_VALUE)
					{
						log.trace("Computing distance between markers "+markerA+" and "+markerB);
						fgDists.setDistance(markerA,markerB,
								fgDists.computeTwoSurfacesDistance(markerA,markerB, 9) );
					}
		}

		final Img<BitType> fgBinaryTmp = doShapePrecalculation ?
				imgFG.factory().imgFactory(new BitType()).create(imgFG) : null;
		final boolean doSphericity = imgFG.numDimensions() == 3;

		//analyze foreground voxels
		for (int marker : bboxes.keySet())
		{
			//found not-yet-processed FG object
			final Interval reducedView = wrapBoxWithInterval(bboxes.get(marker));
			final IntervalView<T> viewRaw = Views.interval(imgRaw, reducedView);
			final IntervalView<UnsignedShortType> viewFgCurr = Views.interval(imgFG, reducedView);
			final IntervalView<UnsignedShortType> viewFgPrev = Views.interval(imgFGprev, reducedView);

			extractFGObjectStats(marker, viewRaw,viewFgCurr,viewFgPrev, data,time);

			if (doShapePrecalculation)
			{
				if (isBoxLargeEnoughForSha(bboxes.get(marker))) {
					final IntervalView<BitType> viewBinTmp = Views.interval(fgBinaryTmp, reducedView);
					data.shaValuesFG.get(time).put(marker,
							doSphericity ? computeSphericity(marker, viewFgCurr, viewBinTmp)
									: computeCircularity(marker, viewFgCurr, viewBinTmp));
				} else
					log.trace("Marker "+marker+" too small for Sha, bbox = "+ Arrays.toString(bboxes.get(marker)));
			}

			if (doDensityPrecalculation) {
				final int closestMarker = fgDists.getClosestNeighbor(marker);
				//record distance only! if some neighbor is found
				if (closestMarker > 0) data.nearDistFG.get(time).put( marker,
						fgDists.getDistance(marker, closestMarker) );
			}
		}
	}

	//---------------------------------------------------------------------/
	/**
	 * Measure calculation happens in two stages. The first/upper stage does
	 * data pre-fetch and calculations to populate the ImgQualityDataCache.
	 * ImgQualityDataCache.calculate() actually does this job. The sort of
	 * calculations is such that the other measures could benefit from
	 * it and re-use it (instead of loading images again and doing same
	 * calculations again), and the results are stored in the cache.
	 * The second/bottom stage is measure-specific. It basically finishes
	 * the measure calculation procedure, possible using data from the cache.
	 *
	 * This function computes the common upper stage of measures.
	 */
	public void calculate(final String imgPath, final double[] resolution,
	                      final String annPath)
	throws IOException, ImgIOException
	{
		//this functions actually only iterates over video folders
		//and calls this.calculateVideo() for every folder

		//test and save the given resolution
		setResolution(resolution);

		//single or multiple (does it contain a "01" subfolder) video situation?
		if (Files.isDirectory( Paths.get(imgPath,"01") ))
		{
			//multiple video situation: paths point on a dataset
			final Logger backupOriginalLog = log;
			int video = 1;
			while (Files.isDirectory( Paths.get(imgPath,(video > 9 ? String.valueOf(video) : "0"+video)) ))
			{
				log = backupOriginalLog.subLogger("video 0"+video);
				final videoDataContainer data = new videoDataContainer(imgPath, video);
				calculateVideo(String.format("%s/%02d",imgPath,video),
				               String.format("%s/%02d_GT",annPath,video), data);
				this.cachedVideoData.add(data);
				++video;
			}
			log = backupOriginalLog;
		}
		else
		{
			//single video situation
			final videoDataContainer data = new videoDataContainer(imgPath, 1);
			calculateVideo(imgPath,annPath,data);
			this.cachedVideoData.add(data);
		}

		//now that we got here, note for what data
		//this cache is valid, see validFor() above
		this.imgPath = imgPath;
		this.annPath = annPath;
	}

	/// this functions processes given video folders and outputs to \e data
	@SuppressWarnings({"unchecked","rawtypes"})
	public void calculateVideo(final String imgPath,
	                           final String annPath,
	                           final videoDataContainer data)
	throws IOException, ImgIOException
	{
		log.info("IMG path: "+imgPath);
		log.info("ANN path: "+annPath);
		//DEBUG//log.info("Computing the common upper part...");

		//we gonna re-use image loading functions...
		final TrackDataCache tCache = new TrackDataCache(log);

		//iterate through the RAW images folder and read files, one by one,
		//find the appropriate file in the annotations folders,
		//and call ClassifyLabels() for every such tripple,
		//
		//check also previous frame for overlap size
		Img<UnsignedShortType> imgFGprev = null;
		//
		int time = 0;
		while (Files.isReadable(
			new File(String.format("%s/t%0"+noOfDigits+"d.tif",imgPath,time)).toPath()))
		{
			//read the image triple (raw image, FG labels, BG label)
			Img<?> img
				= tCache.ReadImage(String.format("%s/t%0"+noOfDigits+"d.tif",imgPath,time));

			Img<UnsignedShortType> imgFG
				= tCache.ReadImageG16(String.format("%s/TRA/man_track%0"+noOfDigits+"d.tif",annPath,time));

			Img<UnsignedByteType> imgBG
				= tCache.ReadImageG8(String.format("%s/BG/mask%0"+noOfDigits+"d.tif",annPath,time));

			ClassifyLabels(time, (Img)img, imgBG, imgFG, imgFGprev, data);

			imgFGprev = null; //be explicit that we do not want this in memory anymore
			imgFGprev = imgFG;
			++time;

			//to be on safe side (with memory)
			img = null;
			imgFG = null;
			imgBG = null;
		}
		imgFGprev = null;

		if (time == 0)
			throw new IllegalArgumentException("No raw image was found!");

		if (data.volumeFG.size() != time)
			throw new IllegalArgumentException("Internal consistency problem with FG data!");

		if (data.avgBG.size() != time)
			throw new IllegalArgumentException("Internal consistency problem with BG data!");
	}
}
