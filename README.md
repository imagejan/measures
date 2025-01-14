Welcome
-------
This is a repository with Java source codes of tools related to the [Cell Tracking Challenge](http://www.celltrackingchallenge.net), and to the quantitative evaluation of biomedical segmentation and tracking in general.
In particular, one can find here:

* Technical (developer-oriented) tracking and segmentation measures: TRA, SEG, DET
* Biological (user-oriented) measures: CT, TF, BC(i), CCA
* Dataset quality measures: SNR, CR, Hetb, Heti, Res, Sha, Spa, Cha, Ove, Mit
* Tracking accuracy evaluation with general [Acyclic Oriented Graphs Measure (AOGM)](http://journals.plos.org/plosone/article?id=10.1371/journal.pone.0144959)

The measures were used in the paper [An objective comparison of cell-tracking algorithms](http://dx.doi.org/10.1038/nmeth.4473) and are,
together with the detection accuracy measure DET, complementing the measures used in the [Challenge](http://www.celltrackingchallenge.net).

The ideas, that are implemented in the tools, are product of a collective collaboration between colleagues who
were at that time affiliated with the following groups: [CIMA](http://www.cima.es),
[CBIA](http://cbia.fi.muni.cz), [Erasmus MC](https://www.erasmusmc.nl/oic/?lang=en), [UC3M](https://www.uc3m.es),
[CSBD](http://www.csbdresden.de/) and [MPI-CBG](http://mpi-cbg.de).

The tools were developed and the page is maintained by [Vladimír Ulman](http://www.fi.muni.cz/~xulman/).
The SEG, TRA/AOGM and DET measures were originally developed in C++ by [Martin Maška](http://cbia.fi.muni.cz/).


Installation
------------
The source codes here compile (for example, using `mvn clean package`) into a library,
into a single `.jar` file. A GUI that exposes most of the functionality of this library exists
in the form of a [Fiji GUI plugin](https://github.com/CellTrackingChallenge/fiji-plugins).

This is a maven project. You can make it available in your maven projects with the following clauses:
```
	<repositories>
		<repository>
			<id>scijava.public</id>
			<url>https://maven.scijava.org/content/groups/public</url>
		</repository>
		<repository>
			<id>it4i</id>
			<url>https://artifactory.cs.vsb.cz/it4i/</url>
		</repository>
	</repositories>

	<dependencies>
		<dependency>
			<groupId>net.celltrackingchallenge</groupId>
			<artifactId>CTC-measures</artifactId>
			<version>1.0.0</version>
		</dependency>
	</dependencies>
```


Example
-------
[Here, one can find example of the API calls](https://github.com/CellTrackingChallenge/measures/blob/e4ac070475b7c50d0d89aecf3c4e74396437eda4/src/test/java/net/celltrackingchallenge/measures/TestMeasures.java#L74) to have the seven (TRA.... CCA) measures calculated over your data.

The data needs to be, however, organized in a special way [(see Naming and Content conventions)](http://public.celltrackingchallenge.net/documents/Naming%20and%20file%20content%20conventions.pdf):

![Example of data layout](src/test/java/net/celltrackingchallenge/measures/test_data.png)


Dataset Measures
----------------
There are also ten DS (DataSet) measures available in this library. These measures shall, well, *measure* quantitatively
the difficulty of a given time-lapse video from ten different cell segmentation and tracking points of view.

The measures work with series of `.tif` files that need to adhere to the format of the Challenge. Additionally, however,
the measures require one more piece of annotation, and that is the **BG mask**, which is stored as a series of gray scale,
8 bits/pixel `.tif` files in which non-zero pixel values **denote a true background** in the images. This gives additional
control to the user to decide and choose exactly which pixels she wants to be considered as the background in the images.
It is especially handy in cases where not necessarily every nuclei/cells were segmented well and thus, for example,
taking a simple complement from all foreground pixels would have actually also covered the non-segmented nuclei/cells.

One more speciality of the DS measures is that they work with **`man_track*.tif`** files, just like the technical measures,
but they **expect different content**. While the Challenge specification requires that the `man_track*.tif` files will
be containing the detection markers, the DS measures further assume that the markers are essentially **full segments**.
The DS measures simply treat pixels with non-zero values as pixels **denoting a true foreground**, e.g., a nucleus or a cell.
The simple-shaped TRA/DET markers, according to their original purpose, are clearly not adequate. Needless to say, for the
DS measures to provide believable numbers, the foreground (and background too) masks must be reasonably accurate.

Here is an example of the expected files layout:

![Example of data layout](src/test/java/net/celltrackingchallenge/measures/DS_data.png)

#### BgMaskCreator
This is a tool to create the BG masks `BG/mask*.tif` from the `TRA/man_track*.tif` full-segments files.

After building the full package with `mvn clean package`, a *fat jar* file `*-with-dependencies.jar` shall appear in the `target` subfolder.
This file is directed to execute the [BgMaskCreator creator](https://github.com/CellTrackingChallenge/measures/blob/master/src/main/java/net/celltrackingchallenge/measures/util/BgMaskCreator.java).

For example, calling

```
$ java -jar target/CTC-measures-0.9.8-SNAPSHOT-jar-with-dependencies.jar
Expecting args: CTCfolder noOfDigits erosionWidth timepointsRange [onwMaskForAll]
```

shall return current manual on how to use the program. These are the parameters:

- *CTCfolder*: the root folder with the data, e.g. `/home/ulman/CTC/DS_measures` from the example above
- *noOfDigits*: how many digits are used in the filenames, typically this is 3, but some CTC datasets use 4
- *erosionWidth*: after complement of the union of the foregound segments is computed (this is the BG mask), it is eroded with
                a circular/spherical SE of the given radius in pixels; typical value is 5, 3 for TRIC, 0 to disable this functionality
- *timepointsRange*: which files shall be used, examples: 0-9 defines first ten time points, also works 0,3,4,6-9,12,14-18,21
- *onwMaskForAll*: if 5th parameter is given (can be any string), the BG mask is a complement of the union of all foreground segments
                 *across* all time points; the program then produces only one file!
		 
Alternatively, one can find this functionality in Fiji, in Plugins -> Cell Tracking Challenge -> Create BG Masks. Don't forget to **enable the Fiji update site `CellTrackingChallenge`**.

#### Dataset Measures from Command Line
This is probably the easiest achieved by operating the Fiji in the head-less mode (that is without the GUI):

```
Fiji.app/ImageJ-linux64 --headless --run "Dataset measures" "imgPath=\"/fullPath/datasetMeasures/Fluo-N3DL-TRIF/01",annPath=\"/fullPath/datasetMeasures/Fluo-N3DL-TRIF/01_GT\",noOfDigits=3,xRes=1.0,yRes=1.0,zRes=1.0,doVerboseLogging=false,calcSNR=true,calcCR=true,calcHeti=true,calcHetb=true,calcRes=true,calcSha=true,calcSpa=true,calcCha=true,calcOve=true,calcMit=true" |tee log.txt
```

That example assumes the folders layout and content as described above, with the root folder being `/fullPath/datasetMeasures/Fluo-N3DL-TRIF`.
Additionally it considers 3 digits are used to denote time points. The image resolution is isotropic. All ten measures should be computed.
The log of the computation is not verbose and shall be displayed both on the screen/terminal as well as into a `log.txt` file.

If one has multiple videos over which a common statistics shall be computed, the videos shall line up in the common root folder
under names `01`, `02`, `03`... and then the command reads like this:

```
Fiji.app/ImageJ-linux64 --headless --run "Dataset measures" "imgPath=\"/fullPath/datasetMeasures/Fluo-N3DL-TRIF",annPath=\"/fullPath/datasetMeasures/Fluo-N3DL-TRIF\",noOfDigits=3,xRes=1.0,yRes=1.0,zRes=1.0,doVerboseLogging=false,calcSNR=true,calcCR=true,calcHeti=true,calcHetb=true,calcRes=true,calcSha=true,calcSpa=true,calcCha=true,calcOve=true,calcMit=true" |tee log.txt
```

It's exactly the same command as above except that the `imgPath` and `annPath` point on the root folder, not on any particular video.

Obviously, this piece has also its GUI Fiji counterpart.
