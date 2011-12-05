/*
 * Copyright (c) 2007, Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * $Id: MspCodeWatcher.java,v 1.24 2010/08/26 14:10:43 nifi Exp $
 */

package se.sics.cooja.mspmote.plugins;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.plaf.basic.BasicComboBoxRenderer;

import org.apache.log4j.Logger;
import org.jdom.Element;

import se.sics.cooja.ClassDescription;
import se.sics.cooja.GUI;
import se.sics.cooja.Mote;
import se.sics.cooja.MotePlugin;
import se.sics.cooja.PluginType;
import se.sics.cooja.Simulation;
import se.sics.cooja.VisPlugin;
import se.sics.cooja.GUI.RunnableInEDT;
import se.sics.cooja.mspmote.MspMote;
import se.sics.cooja.mspmote.MspMoteType;
import se.sics.cooja.util.StringUtils;
import se.sics.mspsim.core.EmulationException;
import se.sics.mspsim.core.MSP430;
import se.sics.mspsim.ui.DebugUI;
import se.sics.mspsim.util.ELFDebug;
import se.sics.mspsim.util.DebugInfo;

@ClassDescription("Msp Code Watcher")
@PluginType(PluginType.MOTE_PLUGIN)
public class MspCodeWatcher extends VisPlugin implements MotePlugin {
  private static Logger logger = Logger.getLogger(MspCodeWatcher.class);
  private Simulation simulation;
  private Observer simObserver;
  private MspMote mspMote;

  private File currentCodeFile = null;
  private int currentLineNumber = -1;

  private JSplitPane leftSplitPane, rightSplitPane;
  private DebugUI assCodeUI;
  private CodeUI sourceCodeUI;
  private BreakpointsUI breakpointsUI;

  private MspBreakpointContainer breakpoints = null;

  private JComboBox fileComboBox;
  private String[] debugInfoMap = null;
  private SortedMap<String,File> sourceFiles;
  
  /**
   * Mini-debugger for MSP Motes.
   * Visualizes instructions, source code and allows a user to manipulate breakpoints.
   *
   * @param mote MSP Mote
   * @param simulationToVisualize Simulation
   * @param gui Simulator
   */
  public MspCodeWatcher(Mote mote, Simulation simulationToVisualize, GUI gui) {
    super("Msp Code Watcher", gui);
    this.mspMote = (MspMote) mote;
    simulation = simulationToVisualize;

    getContentPane().setLayout(new BorderLayout());

    /* Breakpoints */
    breakpoints = mspMote.getBreakpointsContainer();
    
    /* Create source file list */
    fileComboBox = new JComboBox();
    fileComboBox.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        sourceFileSelectionChanged();
      }
    });
    fileComboBox.setRenderer(new BasicComboBoxRenderer() {
      public Component getListCellRendererComponent(JList list, Object value,
          int index, boolean isSelected, boolean cellHasFocus) {
        if (isSelected) {
          setBackground(list.getSelectionBackground());
          setForeground(list.getSelectionForeground());
          if (index > 0) {
            list.setToolTipText(sourceFiles.values().toArray(new File[0])[index-1].getPath());
          }
        } else {
          setBackground(list.getBackground());
          setForeground(list.getForeground());
        }
        setFont(list.getFont());
        setText((value == null) ? "" : value.toString());
        return this;
      }
    });
    updateFileComboBox();
    
    /* Browse code control (north) */
    JButton currentFileButton = new JButton(currentFileAction);
    JButton mapButton = new JButton(mapAction);
    
    Box browseBox = Box.createHorizontalBox();
    browseBox.add(Box.createHorizontalStrut(10));
    browseBox.add(new JLabel("Program counter: "));
    browseBox.add(currentFileButton);
    browseBox.add(Box.createHorizontalGlue());
    browseBox.add(new JLabel("Browse: "));
    browseBox.add(fileComboBox);
    browseBox.add(Box.createHorizontalStrut(10));
    browseBox.add(mapButton);
    browseBox.add(Box.createHorizontalStrut(10));

    mapAction.putValue(Action.NAME, "Map");

    
    /* Execution control panel (south) */
    JPanel controlPanel = new JPanel();
    JButton button = new JButton(stepIntoAction);
    JButton button2 = new JButton(stepOverAction);
    JButton button3 = new JButton(callReturnAction);
    JButton button4 = new JButton(continuePauseAction);
    JButton button5 = new JButton(stepCodeLine);
    stepIntoAction.putValue(Action.NAME, "Step into");
    stepOverAction.putValue(Action.NAME, "Step over");
    callReturnAction.putValue(Action.NAME, "Return call");
    continuePauseAction.putValue(Action.NAME, "Continue");
    stepCodeLine.putValue(Action.NAME, "Step line of code");
    controlPanel.add(button4);
    controlPanel.add(button);
    controlPanel.add(button2);
    controlPanel.add(button3);
    controlPanel.add(button5);

    
    /* Main components: assembler and C code + breakpoints (center) */
    assCodeUI = new DebugUI(this.mspMote.getCPU(), true);
    breakpointsUI = new BreakpointsUI(breakpoints, this);
    sourceCodeUI = new CodeUI(breakpoints);
    leftSplitPane = new JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        new JScrollPane(assCodeUI),
        new JScrollPane(breakpointsUI)
    );
    leftSplitPane.setOneTouchExpandable(true);
    leftSplitPane.setDividerLocation(0.1);
    rightSplitPane = new JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        leftSplitPane,
        new JScrollPane(sourceCodeUI)
        );
    rightSplitPane.setOneTouchExpandable(true);
    rightSplitPane.setDividerLocation(0.1);

    add(BorderLayout.NORTH, browseBox);
    add(BorderLayout.CENTER, rightSplitPane);
    add(BorderLayout.SOUTH, controlPanel);


    continuePauseAction.setEnabled(true);
    
    /* Observe when simulation starts/stops */
    simulation.addObserver(simObserver = new Observer() {
      public void update(Observable obs, Object obj) {
        if (!simulation.isRunning()) {
          continuePauseAction.putValue(Action.NAME, "Continue");
          stepIntoAction.setEnabled(true);
          stepOverAction.setEnabled(true);
          callReturnAction.setEnabled(true);
          stepCodeLine.setEnabled(true);
          updateInfo();
        } else {
          continuePauseAction.putValue(Action.NAME, "Pause");
          stepIntoAction.setEnabled(false);
          stepOverAction.setEnabled(false);
          callReturnAction.setEnabled(false);
          stepCodeLine.setEnabled(false);
        }
      }
    });

    setSize(750, 500);
    updateInfo();
  }

  private void updateFileComboBox() {
    sourceFiles = getSourceFiles(mspMote, debugInfoMap);
    fileComboBox.removeAllItems();
    fileComboBox.addItem("[view sourcefile]");
    for (String s: sourceFiles.keySet()) {
      fileComboBox.addItem(s);
    }
    fileComboBox.setSelectedIndex(0);
  }

  public void displaySourceFile(File file, final int line) {
	  logger.info("Called displayFile with file = "+file+" and line = "+line);

	  /* Check for file existence */
	  if (file != null && sourceFiles.containsKey(file.getName())) {
		  file = sourceFiles.get(file.getName());
	  } else return;
		  
    if (sourceCodeUI.displayedFile != null &&
        file.compareTo(sourceCodeUI.displayedFile) == 0) {
      /* No need to reload source file */
      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          sourceCodeUI.displayLine(line);
        }
      });
      return;
    }
    

    /* Load source file from disk */
    String[] codeData = readTextFile(file);
    if (codeData == null) {
      return;
    }
    sourceCodeUI.displayNewCode(file, codeData, line);
  }

  private void sourceFileSelectionChanged() {
    int index = fileComboBox.getSelectedIndex();
    if (index <= 0) {
      return;
    }

    File selectedFile = sourceFiles.get(fileComboBox.getSelectedItem());
    displaySourceFile(selectedFile, -1);
  }

  private void updateInfo() {
    /* Instructions */
    assCodeUI.updateRegs();
    assCodeUI.repaint();

    /* Source */
    updateCurrentSourceCodeFile();
    if (currentCodeFile == null) {
      currentFileAction.setEnabled(false);
      currentFileAction.putValue(Action.NAME, "[unknown]");
      currentFileAction.putValue(Action.SHORT_DESCRIPTION, null);
      return;
    }
    currentFileAction.setEnabled(true);
    currentFileAction.putValue(Action.NAME, currentCodeFile.getName() + ":" + currentLineNumber);
    currentFileAction.putValue(Action.SHORT_DESCRIPTION, currentCodeFile.getAbsolutePath() + ":" + currentLineNumber);
    fileComboBox.setSelectedIndex(0);

    displaySourceFile(currentCodeFile, currentLineNumber);
  }

  public void closePlugin() {
    simulation.deleteObserver(simObserver);
  }

  private void updateCurrentSourceCodeFile() {
    currentCodeFile = null;

    try {
      ELFDebug debug = ((MspMoteType)mspMote.getType()).getELF().getDebug();
      if (debug == null) {
        return;
      }
      DebugInfo debugInfo = debug.getDebugInfo(mspMote.getCPU().reg[MSP430.PC]);
      if (debugInfo == null) {
        return;
      }

      currentCodeFile = new File(debugInfo.getFile());
      currentLineNumber = debugInfo.getLine();
      
    } catch (Exception e) {
      logger.fatal("Exception: " + e.getMessage(), e);
      currentCodeFile = null;
      currentLineNumber = -1;
    }
  }

  private void tryMapDebugInfo() {
    final String[] debugFiles;
    try {
      ELFDebug debug = ((MspMoteType)mspMote.getType()).getELF().getDebug();
      if (debug == null) {
        logger.fatal("Error: No debug information is available");
        return;
      }
      debugFiles = debug.getSourceFiles();
    } catch (IOException e1) {
      logger.fatal("Error: " + e1.getMessage(), e1);
      return;
    }
    debugInfoMap = new RunnableInEDT<String[]>() {
      public String[] work() {
        /* Select which source file to use */
        int counter = 0, n;
        File correspondingFile = null;
        while (true) {
          n = JOptionPane.showOptionDialog(GUI.getTopParentContainer(),
              "Choose which source file to manually locate.\n\n" +
              "Some source files may not exist, as debug info is also inherited from the toolchain.\n" +
              "\"Next\" selects the next source file in the debug info.\n\n" +
              (counter+1) + "/" + debugFiles.length + ": " + debugFiles[counter],
              "Select source file to locate", JOptionPane.YES_NO_CANCEL_OPTION,
              JOptionPane.QUESTION_MESSAGE, null, 
              new String[] { "Next", "Locate", "Cancel"}, "Next");
          if (n == JOptionPane.CANCEL_OPTION) {
            return null;
          }
          if (n == JOptionPane.NO_OPTION) {
            /* Locate file */
            final String filename = new File(debugFiles[counter]).getName();
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileFilter() {
              public boolean accept(File file) {
                if (file.isDirectory()) { return true; }
                if (file.getName().equals(filename)) {
                  return true;
                }
                return false;
              }
              public String getDescription() {
                return "Source file " + filename;
              }
            });
            int returnVal = fc.showOpenDialog(GUI.getTopParentContainer());
            if (returnVal == JFileChooser.APPROVE_OPTION) {
              correspondingFile = fc.getSelectedFile();
              break;
            }
          }

          if (n == JOptionPane.YES_OPTION) {
            /* Next file */
            counter = (counter+1) % debugFiles.length;
          }
        }

        /* Match files */
        try {
          String canonDebug = debugFiles[counter];
          String canonSelected = correspondingFile.getCanonicalFile().getPath().replace('\\', '/');

          int offset = 0;
          while (canonDebug.regionMatches(
              true,
              canonDebug.length()-offset,
              canonSelected, canonSelected.length()-offset,
              offset)) {
            offset++;
            if (offset >= canonDebug.length() ||
                offset >= canonSelected.length())
              break;
          }
          offset--;
          String replace = canonDebug.substring(0, canonDebug.length() - offset);
          String replacement = canonSelected.substring(0, canonSelected.length() - offset);

          {
            JTextField replaceInput = new JTextField(replace);
            replaceInput.setEditable(true);
            JTextField replacementInput = new JTextField(replacement);
            replacementInput.setEditable(true);

            Box box = Box.createVerticalBox();
            box.add(new JLabel("Debug info file:"));
            box.add(new JLabel(canonDebug));
            box.add(new JLabel("Selected file:"));
            box.add(new JLabel(canonSelected));
            box.add(Box.createVerticalStrut(20));
            box.add(new JLabel("Replacing:"));
            box.add(replaceInput);
            box.add(new JLabel("with:"));
            box.add(replacementInput);

            JOptionPane optionPane = new JOptionPane();
            optionPane.setMessage(box);
            optionPane.setMessageType(JOptionPane.INFORMATION_MESSAGE);
            optionPane.setOptions(new String[] { "OK" });
            optionPane.setInitialValue("OK");
            JDialog dialog = optionPane.createDialog(
                GUI.getTopParentContainer(), 
                "Mapping debug info to real sources");
            dialog.setVisible(true);
            
            replace = replaceInput.getText();
            replacement = replacementInput.getText();
          }
          
          replace = replace.replace('\\', '/');
          replacement = replacement.replace('\\', '/');
          return new String[] { replace, replacement };
        } catch (IOException e) {
          logger.fatal("Error: " + e.getMessage(), e);
          return null;
        }
      }
    }.invokeAndWait();
    updateFileComboBox();
  }
  
  private static SortedMap<String, File> getSourceFiles(MspMote mote, String[] map) {
    final String[] sourceFiles;
    try {
      ELFDebug debug = ((MspMoteType)mote.getType()).getELF().getDebug();
      if (debug == null) {
        logger.fatal("Error: No debug information is available");
        return new TreeMap<String, File>();
      }
      sourceFiles = debug.getSourceFiles();
    } catch (IOException e1) {
      logger.fatal("Error: " + e1.getMessage(), e1);
      return null;
    }
    File contikiSource = mote.getType().getContikiSourceFile();
    if (contikiSource != null) {
      try {
        contikiSource = contikiSource.getCanonicalFile();
      } catch (IOException e1) {
      }
    }
    
    /* Verify that files exist */
    ArrayList<File> existing = new ArrayList<File>();
    for (String sourceFile: sourceFiles) {

      /* Debug info to source file map */
      sourceFile = sourceFile.replace('\\', '/');
      if (map != null && map.length == 2) {
        if (sourceFile.startsWith(map[0])) {
          sourceFile = sourceFile.replace(map[0], map[1]);
        }
      }

      /* Nasty Cygwin-Windows fix */
      if (sourceFile.contains("/cygdrive/")) {
        int index = sourceFile.indexOf("/cygdrive/");
        char driveCharacter = sourceFile.charAt(index+10);
        sourceFile = sourceFile.replace("/cygdrive/" + driveCharacter + "/", driveCharacter + ":/");
      }

      File file = new File(sourceFile);
      try {
        file = file.getCanonicalFile();
      } catch (IOException e1) {
      }
      if (!GUI.isVisualizedInApplet()) {
        if (file.exists() && file.isFile()) {
          existing.add(file);
        } else {
          logger.warn("Can't locate source file, skipping: " + file.getPath());
        }
      } else {
        /* Accept all files without existence check */
        existing.add(file);
      }
    }

    /* If no files were found, suggest map function */
    if (sourceFiles.length > 0 && existing.isEmpty() && GUI.isVisualized()) {
      new RunnableInEDT<Boolean>() {
        public Boolean work() {
          JOptionPane.showMessageDialog(
              GUI.getTopParentContainer(),
              "The firmware debug info specifies " + sourceFiles.length + " source files.\n" +
              "However, Msp Code Watcher could not find any of these files.\n" +
              "Make sure the source files were not moved after the firmware compilation.\n" +
              "\n" +
              "If you want to manually locate the sources, click \"Map\" button.",
              "No source files found", 
              JOptionPane.WARNING_MESSAGE);
          return true;
        }
      }.invokeAndWait();
    }

    /* Sort alphabetically */
    ArrayList<File> sorted = new ArrayList<File>();
    for (File file: existing) {
      int index = 0;
      for (index=0; index < sorted.size(); index++) {
        if (file.getName().compareToIgnoreCase(sorted.get(index).getName()) < 0) {
          break;
        }
      }
      sorted.add(index, file);
    }
    
    /* Add Contiki source first */
    if (contikiSource != null && contikiSource.exists()) {
      sorted.add(0, contikiSource);
    }

    /* Convert to Hashtable */
    SortedMap<String, File> sourceFilesHashtable = new TreeMap<String,File>();
    for (File f: sorted) sourceFilesHashtable.put(f.getName(),f);
    return sourceFilesHashtable;
  }

  /**
   * Tries to open and read given text file.
   *
   * @param file File
   * @return Line-by-line text in file
   */
  public static String[] readTextFile(File file) {
    if (GUI.isVisualizedInApplet()) {
      /* Download from web server instead */
      String path = file.getPath();

      /* Extract Contiki build path */
      String contikiBuildPath = GUI.getExternalToolsSetting("PATH_CONTIKI_BUILD");
      String contikiWebPath = GUI.getExternalToolsSetting("PATH_CONTIKI_WEB");

      if (!path.startsWith(contikiBuildPath)) {
        return null;
      }

      try {
        /* Replace Contiki parent path with web server code base */
        path = contikiWebPath + '/' + path.substring(contikiBuildPath.length());
        path = path.replace('\\', '/');
        URL url = new URL(GUI.getAppletCodeBase(), path);
        String data = StringUtils.loadFromURL(url);
        return data!=null?data.split("\n"):null;
      } catch (MalformedURLException e) {
        logger.warn("Failure to read source code: " + e);
        return null;
      } catch (IOException e) {
        logger.warn("Failure to read source code: " + e);
        return null;
      }
    }

    String data = StringUtils.loadFromFile(file);
    return data!=null?data.split("\n"):null;
  }

  public Collection<Element> getConfigXML() {
    Vector<Element> config = new Vector<Element>();
    Element element;
    
    element = new Element("split_1");
    element.addContent("" + leftSplitPane.getDividerLocation());
    config.add(element);
    
    element = new Element("split_2");
    element.addContent("" + rightSplitPane.getDividerLocation());
    config.add(element);
    
    return config;
  }

  public boolean setConfigXML(Collection<Element> configXML, boolean visAvailable) {
    for (Element element : configXML) {
      if (element.getName().equals("split_1")) {
        leftSplitPane.setDividerLocation(Integer.parseInt(element.getText()));
      } else if (element.getName().equals("split_2")) {
        rightSplitPane.setDividerLocation(Integer.parseInt(element.getText()));
      }
    }
    return true;
  }

  private AbstractAction currentFileAction = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      if (currentCodeFile == null) {
        return;
      }
      displaySourceFile(currentCodeFile, currentLineNumber);
    }
  };

  private AbstractAction mapAction = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      tryMapDebugInfo();
    }
  };

  private AbstractAction stepIntoAction = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      try {
        mspMote.getCPU().stepInstructions(1);
      } catch (EmulationException ex) {
        logger.fatal("Error: ", ex);
      }
      updateInfo();
    }
  };

  private AbstractAction stepOverAction = new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
      try {
    	int pc, newpc, instruction;

    	// get CPU reference
    	MSP430 cpu = mspMote.getCPU();
    	
    	// read program counter
        pc = cpu.readRegister(MSP430.PC);
        // read current instruction
        instruction = cpu.read(pc, MSP430.MODE_WORD);
        if ((instruction & 0xff00) == 0x1800) { // Long instruction?
        	pc += 2;
        	instruction = cpu.read(pc, MSP430.MODE_WORD);
        }
        
        // get opcode, suppose it's a call
        if ((instruction & 0xff80) == MSP430.CALL)
        {
        	do {
        		cpu.stepInstructions(1);
        		newpc = cpu.readRegister(MSP430.PC);
        	} while (newpc != pc + 4); // Big risk here :)
        } else {
        	cpu.stepInstructions(1);
        }
       } catch (EmulationException ex) {
        logger.fatal("Error: ", ex);
      }
      updateInfo();
    }
  };

  private AbstractAction callReturnAction = new AbstractAction() {
	    public void actionPerformed(ActionEvent e) {
	      try {
	    	int pc, instruction, opleft = 1000000, depth = 1;

	    	// get CPU reference
	    	MSP430 cpu = mspMote.getCPU();
	    	
	    	while (depth > 0 && opleft-- > 0) {
		    	// read program counter
		        pc = cpu.readRegister(MSP430.PC);
		        // read current instruction
		        instruction = cpu.read(pc, MSP430.MODE_WORD);
		        if ((instruction & 0xff00) == 0x1800) { // Long instruction?
		        	pc += 2;
		        	instruction = cpu.read(pc, MSP430.MODE_WORD);
		        }
		        if (instruction == MSP430.RETURN) depth--;
		        else if ((instruction & 0xff80) == MSP430.CALL) depth++; 
		        cpu.stepInstructions(1);
	    	} // Big risk here :)
		      if (depth > 0) logger.info("Return not found! Stopped after 1000000 cycles");
	       } catch (EmulationException ex) {
	        logger.fatal("Error: ", ex);
	      }
	      updateInfo();
	    }
	  };

	  private AbstractAction stepCodeLine = new AbstractAction() {
		    public void actionPerformed(ActionEvent e) {
		      try {
		    	int pc, instruction, opleft = 1000000, oldline, newline;
		    	String oldfile, newfile;

		    	// get debug reference and cpu
		    	ELFDebug debug = ((MspMoteType)mspMote.getType()).getELF().getDebug();
		    	if (debug == null) {
		    		return;
		    	}
		    	MSP430 cpu = mspMote.getCPU();
		    	if (cpu == null) {
		    		return;
		    	}

		    	// get old file and line of code
		    	DebugInfo debugInfo = debug.getDebugInfo(cpu.reg[MSP430.PC]);
		    	if (debugInfo != null) {
			    	oldfile = debugInfo.getFile();
			    	oldline = debugInfo.getLine();
		    	} else {
		    		oldfile = null;
		    		oldline = 0;
		    	}

		    	do {
			    	// get new line of code
		    		do {
		    			cpu.stepInstructions(1);
		    		} while ((debugInfo = debug.getDebugInfo(cpu.reg[MSP430.PC])) == null || 
		    				(newfile = debugInfo.getFile()) == null);
		    		
			    	newline = debugInfo.getLine();
			    	opleft--;
		    	} while (oldline == newline && oldfile.equals(newfile) && opleft-- > 0);
			      if (oldline == newline && oldfile.equals(newfile)) logger.info("Instruction not switched! Stopped after 1000000 cycles");
		       } catch (EmulationException ex) {
		        logger.fatal("Emulation exception: ", ex);
		      } catch (IOException ex) {
			    logger.fatal("IO exception: ", ex);
			}
		      updateInfo();
		    }
		  };
		  
	  private AbstractAction continuePauseAction = new AbstractAction() {
		    public void actionPerformed(ActionEvent e) {
		      try {
		        if (simulation.isRunning()) {
		        	simulation.stopSimulation();
				    updateInfo();
		        }
		        else simulation.startSimulation();
		      } catch (EmulationException ex) {
		        logger.fatal("Error: ", ex);
		      }
		    }
		  };
	  
  public Mote getMote() {
    return mspMote;
  }

}
