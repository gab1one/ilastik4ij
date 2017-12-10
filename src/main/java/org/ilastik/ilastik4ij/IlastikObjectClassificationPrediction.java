/**
 * MIT License
 *
 * Copyright (c) 2017 ilastik
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Author: Carsten Haubold
 */
package org.ilastik.ilastik4ij;

import org.ilastik.ilastik4ij.util.IlastikUtilities;
import org.ilastik.ilastik4ij.hdf5.Hdf5DataSetReader;
import org.ilastik.ilastik4ij.hdf5.Hdf5DataSetWriterFromImgPlus;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.options.OptionsService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;

/**
 *
 */
@Plugin(type = Command.class, headless = true, menuPath = "Plugins>ilastik>Run Object Classification Prediction")
public class IlastikObjectClassificationPrediction implements Command {

    // needed services:
    @Parameter
    LogService log;

    @Parameter
    OptionsService optionsService;
    
    @Parameter
    DatasetService datasetService;

    // own parameters:
    @Parameter(label = "Trained ilastik project file")
    private File projectFileName;

    @Parameter(label = "Raw image")
    Dataset inputRawImage;

    @Parameter(label = "Pixel Probability or Segmentation image")
    Dataset inputProbOrSegImage;

    @Parameter(label = "Second Input Type", choices = {"Segmentation", "Probabilities"}, style = "radioButtonHorizontal")
    private String secondInputType = "Probabilities";
    
//    @Parameter(label = "Selected Output Type", choices = {"Class Label Image", "Feature Table"}, style = "radioButtonHorizontal")
//    private String selectedOutputType = "Class Label Image";

    @Parameter(type = ItemIO.OUTPUT)
    ImgPlus predictions;

    private IlastikOptions ilastikOptions = null;

    /**
     * Run method that calls ilastik
     */
    @Override
    public void run() {
        ilastikOptions = optionsService.getOptions(IlastikOptions.class);

        if (ilastikOptions == null) {
            log.error("Could not find configured ilastik options!");
            return;
        }

        if (!ilastikOptions.isConfigured()) {
            log.error("ilastik service must be configured before use!");
            return;
        }

        String tempInFileName = null;
        String tempProbOrSegFileName = null;
        String tempOutFileName = null;

        try {
            try {
                tempInFileName = IlastikUtilities.getTemporaryFileName("_raw.h5");
            } catch (IOException e) {
                log.error("Could not create a temporary file for sending raw data to ilastik");
                e.printStackTrace();
                return;
            }

            try {
                tempProbOrSegFileName = IlastikUtilities.getTemporaryFileName("_probOrSeg.h5");
            } catch (IOException e) {
                log.error("Could not create a temporary file for sending prob or seg data to ilastik");
                e.printStackTrace();
                return;
            }

            // we do not want to compress probabilities (doesn't help), but segmentations really benefit from it
            int compressionLevel = 0;
            if (secondInputType.equals("Segmentation")) {
                compressionLevel = 9;
            }
            
            log.info("Dumping raw input image to temporary file " + tempInFileName);
            new Hdf5DataSetWriterFromImgPlus(inputRawImage.getImgPlus(), tempInFileName, "data", 0, log).write();
            
            log.info("Dumping secondary input image to temporary file " + tempProbOrSegFileName);
            new Hdf5DataSetWriterFromImgPlus(inputProbOrSegImage.getImgPlus(), tempProbOrSegFileName, "data", compressionLevel, log).write();


            try {
                tempOutFileName = IlastikUtilities.getTemporaryFileName("_outPred.h5");
            } catch (IOException e) {
                log.error("Could not create a temporary file for obtaining the results from ilastik");
                e.printStackTrace();
                return;
            }

            runIlastik(tempInFileName, tempProbOrSegFileName, tempOutFileName);
            log.info("Reading resulting probabilities from " + tempOutFileName);

            predictions = new Hdf5DataSetReader(tempOutFileName, "exported_data", "tzyxc", log, datasetService).read();
            predictions.setName("Object Predictions");
        } catch (final Exception e) {
            log.warn("Ilastik Object Classification Prediction failed");
		} finally {
			log.info("Cleaning up");
			// get rid of temporary files
			if (tempInFileName != null) {
				new File(tempInFileName).delete();
			}
			if (tempProbOrSegFileName != null) {
				new File(tempProbOrSegFileName).delete();
			}
			if (tempOutFileName != null) {
				new File(tempOutFileName).delete();
			}
		}
    }

    private void runIlastik(String tempInRawFileName, String tempProbOrSegFilename, String tempOutFileName) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(ilastikOptions.getExecutableFilePath());
        commandLine.add("--headless");
        commandLine.add("--project=" + projectFileName.getAbsolutePath());
        commandLine.add("--output_filename_format=" + tempOutFileName);
        commandLine.add("--output_format=hdf5");
        commandLine.add("--output_axis_order=tzyxc");
        commandLine.add("--raw_data=" + tempInRawFileName);

        if (secondInputType.equals("Segmentation")) {
            commandLine.add("--segmentation_image=" + tempProbOrSegFilename);
        } else {
            commandLine.add("--prediction_maps=" + tempProbOrSegFilename);
        }
        
//        if (selectedOutputType.equals("Feature Table"))
//        {
//            commandLine.add("--table_filename="+tempOutFileName);
//            commandLine.add("--table_only");
//        }

        log.info("Running ilastik headless command:");
        log.info(commandLine);

        ProcessBuilder pB = new ProcessBuilder(commandLine);
        ilastikOptions.configureProcessBuilderEnvironment(pB);

        // run ilastik
        Process p;
        try {
            p = pB.start();

            // write ilastik output to IJ log
            IlastikUtilities.redirectOutputToLogService(p.getInputStream(), log, false);
            IlastikUtilities.redirectOutputToLogService(p.getErrorStream(), log, true);

            try {
                p.waitFor();
            } catch (InterruptedException cee) {
                log.warn("Execution got interrupted");
                p.destroy();
            }

            // 0 indicates successful execution
            if (p.exitValue() != 0) {
                log.error("ilastik crashed");
                throw new IllegalStateException("Execution of ilastik was not successful.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        log.info("ilastik finished successfully!");
    }

}
