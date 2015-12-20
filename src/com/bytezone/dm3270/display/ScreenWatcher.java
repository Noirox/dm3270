package com.bytezone.dm3270.display;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bytezone.dm3270.application.Site;
import com.bytezone.dm3270.assistant.Dataset;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;

public class ScreenWatcher
{
  private static final String[] tsoMenus =
      { "Menu", "List", "Mode", "Functions", "Utilities", "Help" };
  private static final String[] pdsMenus =
      { "Menu", "Functions", "Confirm", "Utilities", "Help" };
  private static final String[] memberMenus =
      { "Menu", "Functions", "Utilities", "Help" };
  private static final String SPLIT_LINE = ".  .  .  .  .  .  .  .  .  .  .  .  .  "
      + ".  .  .  .  .  .  .  .  .  .  .  .  .  .";
  private static final String EXCLUDE_LINE = "-  -  -  -  -  -  -  -  -  -  -  -";
  private static final String segment = "[A-Z@#$][-A-Z0-9@#$]{0,7}";
  private static final Pattern datasetNamePattern =
      Pattern.compile (segment + "(\\." + segment + "){0,21}");
  private static final Pattern memberNamePattern = Pattern.compile (segment);
  private static final DateFormat df = new SimpleDateFormat ("dd/MM/yyyy HH:mm.ss");

  private static final String ispfScreen = "ISPF Primary Option Menu";
  private static final String zosScreen = "z/OS Primary Option Menu";
  private static final String ispfShell = "ISPF Command Shell";

  private final FieldManager fieldManager;
  private final ScreenDimensions screenDimensions;
  private final Site server;

  private final List<Dataset> datasets = new ArrayList<> ();
  private final List<Dataset> members = new ArrayList<> ();
  private final List<String> recentDatasets = new ArrayList<> ();

  private String datasetsMatching;
  private String datasetsOnVolume;

  private Field tsoCommandField;
  private boolean isTSOCommandScreen;
  private boolean isDatasetList;
  private boolean isMemberList;
  private boolean isSplitScreen;
  private int promptFieldLine;

  private String currentDataset = "";
  private String singleDataset = "";
  private String userid = "";
  private String prefix = "";

  private final MenuItem menuItemUpload;
  private final MenuItem menuItemDownload;
  private Site replaySite;

  public ScreenWatcher (FieldManager fieldManager, ScreenDimensions screenDimensions,
      Site server)
  {
    this.fieldManager = fieldManager;
    this.screenDimensions = screenDimensions;
    this.server = server;

    menuItemUpload = getMenuItem ("Upload", e -> upload (), KeyCode.U);
    menuItemDownload = getMenuItem ("Download", e -> download (), KeyCode.D);
  }

  public void setReplayServer (Site serverSite)
  {
    replaySite = serverSite;
  }

  public MenuItem getMenuItemUpload ()
  {
    return menuItemUpload;
  }

  public MenuItem getMenuItemDownload ()
  {
    return menuItemDownload;
  }

  private MenuItem getMenuItem (String text, EventHandler<ActionEvent> eventHandler,
      KeyCode keyCode)
  {
    MenuItem menuItem = new MenuItem (text);
    menuItem.setOnAction (eventHandler);
    menuItem
        .setAccelerator (new KeyCodeCombination (keyCode, KeyCombination.SHORTCUT_DOWN));
    return menuItem;
  }

  private void upload ()
  {
    String fileName = (String) menuItemDownload.getUserData ();

    String cmd = showUploadDialog (fileName);
    if ("OK".equals (cmd))
      System.out.println ("upload " + menuItemUpload.getUserData ());
  }

  private void download ()
  {
    Site site = server != null ? server : replaySite != null ? replaySite : null;
    String folderName = site != null ? site.getFolder () : "<no site>";

    String userHome = System.getProperty ("user.home");
    Path homePath = Paths.get (userHome, "dm3270", "files", folderName);
    if (Files.notExists (homePath))
    {
      // show dialog here
      System.out.println ("Path does not exist: " + homePath);
      return;
    }

    int baseLength = userHome.length () + 1;
    String datasetName = showDownloadDialog (homePath, baseLength);
    if (datasetName.isEmpty ())
      System.out.println ("Cancelled");
    else
      System.out.println ("Download: " + datasetName);
  }

  // Determine the path of the folder in which the dataset should be stored
  private String getSaveFolderName (Path homePath, String datasetName)
  {
    // convert the dataset name into a potential path of folder names
    String[] segments = datasetName.split ("\\.");      // split into segments
    int last = segments.length - 1;

    // if the last segment contains a pds member name, remove it
    if (last >= 0 && segments[last].endsWith (")"))
    {
      int pos = segments[last].indexOf ('(');
      segments[last] = segments[last].substring (0, pos);
    }

    int nextSegment = 0;
    String buildPath = homePath.toString ();

    while (nextSegment < segments.length)
    {
      Path nextPath = Paths.get (buildPath, segments[nextSegment++]);
      if (Files.notExists (nextPath) || !Files.isDirectory (nextPath))
        break;
      buildPath = nextPath.toString ();

      Path filePath = Paths.get (buildPath, datasetName);
      System.out.println (filePath);
      if (Files.exists (filePath))
        System.out.println ("File exists at: " + filePath);
    }
    return buildPath;
  }

  private String showDownloadDialog (Path homePath, int baseLength)
  {
    Label label1 = new Label ("Download");
    Label label3 = new Label ("To folder");
    Label label4 = new Label ();
    Label label5 = new Label ("Action");
    Label label6 = new Label ();
    Label label7 = new Label ("File date");
    Label label8 = new Label ();

    ComboBox<String> box = new ComboBox<> ();
    box.setItems (FXCollections.observableList (recentDatasets));
    box.setOnAction (event -> refresh (box, homePath, label4, label6, label8,
                                       baseLength));
    box.getSelectionModel ().select (singleDataset);
    refresh (box, homePath, label4, label6, label8, baseLength);

    Dialog<String> dialog = new Dialog<> ();

    GridPane grid = new GridPane ();
    grid.setPadding (new Insets (10, 35, 10, 20));
    grid.add (label1, 1, 1);
    grid.add (box, 2, 1);
    grid.add (label3, 1, 2);
    grid.add (label4, 2, 2);
    grid.add (label5, 1, 3);
    grid.add (label6, 2, 3);
    grid.add (label7, 1, 4);
    grid.add (label8, 2, 4);
    grid.setHgap (10);
    grid.setVgap (15);
    dialog.getDialogPane ().setContent (grid);

    ButtonType btnTypeOK = new ButtonType ("OK", ButtonData.OK_DONE);
    ButtonType btnTypeCancel = new ButtonType ("Cancel", ButtonData.CANCEL_CLOSE);
    dialog.getDialogPane ().getButtonTypes ().addAll (btnTypeOK, btnTypeCancel);
    dialog.setResultConverter (btnType ->
    {
      if (btnType == btnTypeOK)
        return box.getSelectionModel ().getSelectedItem ();
      return "";
    });

    return dialog.showAndWait ().get ();
  }

  private void refresh (ComboBox<String> box, Path homePath, Label folderLabel,
      Label actionLabel, Label dateLabel, int baseLength)
  {
    String datasetSelected = box.getSelectionModel ().getSelectedItem ();
    String saveFolderName = getSaveFolderName (homePath, datasetSelected);
    Path saveFile = Paths.get (saveFolderName, datasetSelected);

    folderLabel.setText (saveFolderName.substring (baseLength));

    if (Files.exists (saveFile))
    {
      try
      {
        BasicFileAttributes attr =
            Files.readAttributes (saveFile, BasicFileAttributes.class);
        dateLabel.setText (df.format (attr.lastModifiedTime ().toMillis ()));
      }
      catch (IOException e)
      {
        dateLabel.setText ("IOException");
      }
      actionLabel.setText ("Overwrite existing file");
    }
    else
    {
      actionLabel.setText ("Create new file");
      dateLabel.setText ("");
    }
  }

  private String showUploadDialog (String fileName)
  {
    Label label1 = new Label ("Upload: ");
    Label label2 = new Label (fileName);
    Label label3 = new Label ("From folder: ");
    Label label4 = new Label ("??");
    //    Label label5 = new Label ("Exists: ");
    //    Label label6 = new Label (Files.exists (path) ? "Yes" : "No");

    Dialog<String> dialog = new Dialog<> ();

    GridPane grid = new GridPane ();
    grid.add (label1, 1, 1);
    grid.add (label2, 2, 1);
    grid.add (label3, 1, 2);
    grid.add (label4, 2, 2);
    //    grid.add (label5, 1, 3);
    //    grid.add (label6, 2, 3);
    grid.setHgap (10);
    grid.setVgap (10);
    dialog.getDialogPane ().setContent (grid);

    ButtonType btnTypeOK = new ButtonType ("OK", ButtonData.OK_DONE);
    ButtonType btnTypeCancel = new ButtonType ("Cancel", ButtonData.CANCEL_CLOSE);
    dialog.getDialogPane ().getButtonTypes ().addAll (btnTypeOK, btnTypeCancel);
    dialog.setResultConverter (btnType ->
    {
      if (btnType == btnTypeOK)
        return "OK";
      return "";
    });

    return dialog.showAndWait ().get ();
  }

  // called by FieldManager after building a new screen
  void check ()
  {
    tsoCommandField = null;
    isTSOCommandScreen = false;
    isDatasetList = false;
    isMemberList = false;
    isSplitScreen = false;
    datasets.clear ();
    members.clear ();
    //    currentDataset = "";
    //    singleDataset = "";
    promptFieldLine = -1;

    List<Field> screenFields = fieldManager.getFields ();
    if (screenFields.size () <= 2)
      return;

    isSplitScreen = checkSplitScreen ();
    if (isSplitScreen)
      return;

    //    checkMenu ();

    if (hasPromptField ())
    {
      if (prefix.isEmpty ())
        checkPrefixScreen (screenFields);       // initial ISPF screen

      isDatasetList = checkDatasetList (screenFields);

      if (isDatasetList)
        System.out.println ("Dataset list");
      else
      {
        isMemberList = checkMemberList (screenFields);
        if (isMemberList)
          System.out.println ("Member list of " + currentDataset);
        else
          checkSingleDataset (screenFields);
      }
    }
    else
      isTSOCommandScreen = checkTSOCommandScreen (screenFields);
  }

  public Field getTSOCommandField ()
  {
    return tsoCommandField;
  }

  public boolean isTSOCommandScreen ()
  {
    return isTSOCommandScreen;
  }

  public String getUserid ()
  {
    return userid;
  }

  public String getPrefix ()
  {
    return prefix;
  }

  public List<Dataset> getDatasets ()
  {
    return datasets;
  }

  public List<Dataset> getMembers ()
  {
    return members;
  }

  public String getCurrentDataset ()
  {
    return currentDataset;
  }

  public String getSingleDataset ()
  {
    return singleDataset;
  }

  private void checkMenu ()
  {
    List<Field> rowFields = fieldManager.getRowFields (0, 1);
    dumpFields (rowFields);

    if (rowFields.size () > 1 && rowFields.size () < 10)
    {
      Field menuField = rowFields.get (0);
      String text = menuField.getText ();
      if (" Menu".equals (text) && menuField.isAlphanumeric () && menuField.isProtected ()
          && menuField.isVisible () && menuField.isIntensified ())
      {
        System.out.println ("Possible menu");
      }
    }
  }

  private boolean checkSplitScreen ()
  {
    return fieldManager.getFields ().parallelStream ()
        .filter (f -> f.isProtected () && f.getDisplayLength () == 79
            && f.getFirstLocation () % screenDimensions.columns == 1
            && SPLIT_LINE.equals (f.getText ()))
        .findAny ().isPresent ();
  }

  private boolean hasPromptField ()
  {
    List<Field> rowFields = fieldManager.getRowFields (1, 3);
    for (int i = 0; i < rowFields.size (); i++)
    {
      Field field = rowFields.get (i);
      String text = field.getText ();
      int column = field.getFirstLocation () % screenDimensions.columns;
      int nextFieldNo = i + 1;

      if (nextFieldNo < rowFields.size () && column == 1
          && ("Command ===>".equals (text) || "Option ===>".equals (text)))
      {
        Field nextField = rowFields.get (nextFieldNo);
        int length = nextField.getDisplayLength ();
        boolean modifiable = nextField.isUnprotected ();
        boolean visible = !nextField.isHidden ();

        if ((length == 66 || length == 48) && visible && modifiable)
        {
          tsoCommandField = nextField;
          promptFieldLine = field.getFirstLocation () / 80;
          return true;
        }
      }
    }

    tsoCommandField = null;
    return false;
  }

  private void checkPrefixScreen (List<Field> screenFields)
  {
    if (screenFields.size () < 74)
      return;

    Field field = screenFields.get (10);
    String heading = field.getText ();
    if (!ispfScreen.equals (heading) && !zosScreen.equals (heading))
      return;

    if (!fieldManager.textMatches (23, " User ID . :", 457))
      return;

    field = screenFields.get (24);
    if (field.getFirstLocation () != 470)
      return;

    userid = field.getText ().trim ();

    if (!fieldManager.textMatches (72, " TSO prefix:", 1017))
      return;

    field = screenFields.get (73);
    if (field.getFirstLocation () != 1030)
      return;

    prefix = field.getText ().trim ();
  }

  private boolean checkTSOCommandScreen (List<Field> screenFields)
  {
    if (screenFields.size () < 19)
      return false;

    if (!fieldManager.textMatches (10, ispfShell))
      return false;

    int workstationFieldNo = 13;
    String workstationText = "Enter TSO or Workstation commands below:";
    if (!fieldManager.textMatches (workstationFieldNo, workstationText))
      if (!fieldManager.textMatches (++workstationFieldNo, workstationText))
        return false;

    if (!listMatchesArray (fieldManager.getMenus (), tsoMenus))
      return false;

    Field field = screenFields.get (workstationFieldNo + 5);
    if (field.getDisplayLength () != 234)
      return false;

    tsoCommandField = field;
    return true;
  }

  private boolean checkDatasetList (List<Field> screenFields)
  {
    if (screenFields.size () < 21)
      return false;

    List<Field> rowFields = fieldManager.getRowFields (2, 2);
    if (rowFields.size () == 0)
      return false;

    String text = rowFields.get (0).getText ();
    if (!text.startsWith ("DSLIST - Data Sets "))
      return false;

    String locationText = "";
    int pos = text.indexOf ("Row ");
    if (pos > 0)
      locationText = text.substring (19, pos).trim ();
    else
      locationText = text.substring (19).trim ();

    datasetsOnVolume = "";
    datasetsMatching = "";

    if (locationText.startsWith ("on volume "))
      datasetsOnVolume = locationText.substring (10);
    else if (locationText.startsWith ("Matching "))
      datasetsMatching = locationText.substring (9);
    else
    {
      // Could be: Matched in list REFLIST
      System.out.println ("Unexpected text: " + locationText);
      return false;
    }

    rowFields = fieldManager.getRowFields (5, 2);
    if (rowFields.size () < 3)
      return false;

    if (!rowFields.get (0).getText ().startsWith ("Command - Enter"))
      return false;

    int screenType = 0;
    int linesPerDataset = 1;
    int nextLine = 7;

    switch (rowFields.size ())
    {
      case 3:
        String heading = rowFields.get (1).getText ().trim ();
        if (heading.startsWith ("Tracks"))
          screenType = 1;
        else if (heading.startsWith ("Dsorg"))
          screenType = 2;
        break;

      case 4:
        String message = rowFields.get (1).getText ().trim ();
        heading = rowFields.get (2).getText ().trim ();
        if ("Volume".equals (heading) && "Message".equals (message))
          screenType = 3;
        break;

      case 6:
        message = rowFields.get (1).getText ().trim ();
        heading = rowFields.get (2).getText ().trim ();
        if ("Volume".equals (heading) && "Message".equals (message))
        {
          List<Field> rowFields2 = fieldManager.getRowFields (nextLine);
          if (rowFields2.size () == 1)
          {
            String line = rowFields2.get (0).getText ().trim ();
            if (line.equals ("Catalog"))
            {
              screenType = 4;
              linesPerDataset = 3;
              nextLine = 9;
            }
            else if (line.startsWith ("--"))
            {
              screenType = 5;
              linesPerDataset = 2;
              nextLine = 8;
            }
            else
              System.out.println ("Expected 'Catalog' or underscores: " + line);
          }
        }
        break;

      default:
        System.out.printf ("Unexpected number of fields: %d%n", rowFields.size ());
    }

    if (screenType == 0)
    {
      System.out.println ("Screen not recognised");
      dumpFields (rowFields);
      return false;
    }

    while (nextLine < screenDimensions.rows)
    {
      rowFields = fieldManager.getRowFields (nextLine, linesPerDataset);
      if (rowFields.size () <= 1)
        break;

      String lineText = rowFields.get (0).getText ();
      if (lineText.length () < 10)
        break;

      String datasetName = lineText.substring (9).trim ();
      if (datasetName.length () > 44)
      {
        System.out.printf ("Dataset name too long: %s%n", datasetName);
        break;
      }
      Matcher matcher = datasetNamePattern.matcher (datasetName);
      if (matcher.matches ())
        addDataset (datasetName, screenType, rowFields);
      else
      {
        // check for excluded datasets
        if (!EXCLUDE_LINE.equals (datasetName))
          System.out.printf ("Invalid dataset name: %s%n", datasetName);

        // what about GDGs?
      }

      nextLine += linesPerDataset;
      if (linesPerDataset > 1)
        nextLine++;                           // skip the row of hyphens
    }

    return true;
  }

  private void addDataset (String datasetName, int screenType, List<Field> rowFields)
  {
    Dataset dataset = new Dataset (datasetName);
    datasets.add (dataset);

    switch (screenType)
    {
      case 1:
        if (rowFields.size () == 2)
          setSpace (dataset, rowFields.get (1).getText (), 6, 11, 15);
        break;

      case 2:
        if (rowFields.size () == 2)
          setDisposition (dataset, rowFields.get (1).getText (), 5, 11, 18);
        break;

      case 3:
        if (rowFields.size () == 3)
          dataset.setVolume (rowFields.get (2).getText ().trim ());
        break;

      case 4:
        if (rowFields.size () == 7)
        {
          dataset.setVolume (rowFields.get (2).getText ().trim ());
          setSpace (dataset, rowFields.get (3).getText (), 6, 10, 14);
          setDisposition (dataset, rowFields.get (4).getText (), 5, 10, 16);
          setDates (dataset, rowFields.get (5).getText ());

          String catalog = rowFields.get (6).getText ().trim ();
          Matcher matcher = datasetNamePattern.matcher (catalog);
          if (matcher.matches ())
            dataset.setCatalog (catalog);
        }
        break;

      case 5:
        if (rowFields.size () >= 3)
        {
          dataset.setVolume (rowFields.get (2).getText ().trim ());
          if (rowFields.size () >= 6)
          {
            setSpace (dataset, rowFields.get (3).getText (), 6, 10, 14);
            setDisposition (dataset, rowFields.get (4).getText (), 5, 10, 16);
            setDates (dataset, rowFields.get (5).getText ());
          }
        }
        break;
    }
  }

  private void setSpace (Dataset dataset, String details, int t1, int t2, int t3)
  {
    if (details.trim ().isEmpty ())
      return;

    if (details.length () >= t1)
      dataset.setTracks (getInteger ("tracks", details.substring (0, t1).trim ()));
    if (details.length () >= t2)
      dataset.setPercentUsed (getInteger ("pct", details.substring (t1, t2).trim ()));
    if (details.length () >= t3)
      dataset.setExtents (getInteger ("ext", details.substring (t2, t3).trim ()));
    if (details.length () > t3)
      dataset.setDevice (details.substring (t3).trim ());
  }

  private void setDisposition (Dataset dataset, String details, int t1, int t2, int t3)
  {
    if (details.trim ().isEmpty ())
      return;

    if (details.length () >= t1)
      dataset.setDsorg (details.substring (0, t1).trim ());
    if (details.length () >= t2)
      dataset.setRecfm (details.substring (t1, t2).trim ());
    if (details.length () >= t3)
      dataset.setLrecl (getInteger ("lrecl", details.substring (t2, t3).trim ()));
    if (details.length () > t3)
      dataset.setBlksize (getInteger ("blksize", details.substring (t3).trim ()));
  }

  private void setDates (Dataset dataset, String details)
  {
    if (details.trim ().isEmpty ())
      return;

    dataset.setCreated (details.substring (0, 11).trim ());
    dataset.setExpires (details.substring (11, 22).trim ());
    dataset.setReferred (details.substring (22).trim ());
  }

  private boolean checkMemberList (List<Field> screenFields)
  {
    if (screenFields.size () < 14)
      return false;

    if (!listMatchesArray (fieldManager.getMenus (), pdsMenus))
      return checkMemberList2 (screenFields);

    Field field = screenFields.get (8);
    int location = field.getFirstLocation ();
    if (location != 161)
      return false;

    String mode = field.getText ().trim ();
    int[] tabs1 = null;
    int[] tabs2 = null;

    switch (mode)
    {
      case "LIBRARY":                             // 3.1
        tabs1 = new int[] { 12, 25, 38, 47 };
        tabs2 = new int[] { 12, 21, 31, 43 };
        break;

      case "EDIT":                                // 3.4:e
      case "BROWSE":                              // 3.4:b
      case "VIEW":                                // 3.4:v
      case "DSLIST":                              // 3.4:m
        tabs1 = new int[] { 9, 21, 33, 42 };
        tabs2 = new int[] { 9, 17, 25, 36 };
        break;
      default:
        System.out.printf ("Unexpected mode1: [%s]%n", mode);
        return false;
    }

    field = screenFields.get (9);
    if (field.getFirstLocation () != 179)
      return false;

    String datasetName = field.getText ().trim ();
    currentDataset = datasetName;

    List<Field> headings = fieldManager.getRowFields (4);

    for (int row = 5; row < screenDimensions.rows; row++)
    {
      List<Field> rowFields = fieldManager.getRowFields (row);
      if (rowFields.size () != 4 || rowFields.get (1).getText ().equals ("**End** "))
        break;

      String memberName = rowFields.get (1).getText ().trim ();
      Matcher matcher = memberNamePattern.matcher (memberName);
      if (!matcher.matches ())
      {
        System.out.printf ("Invalid member name: %s%n", memberName);
        break;
      }
      String details = rowFields.get (3).getText ();

      Dataset member = new Dataset (datasetName + "(" + memberName + ")");
      members.add (member);

      if (headings.size () == 7 || headings.size () == 10)
        screenType1 (member, details, tabs1);
      else if (headings.size () == 13)
        screenType2 (member, details, tabs2);
    }

    return true;
  }

  private boolean checkMemberList2 (List<Field> screenFields)
  {
    if (!listMatchesArray (fieldManager.getMenus (), memberMenus))
      return false;

    Field field = screenFields.get (7);
    int location = field.getFirstLocation ();
    if (location != 161)
      return false;

    String mode = field.getText ().trim ();
    if (!(mode.equals ("EDIT")      // Menu option 1 (browse mode not selected)
        || mode.equals ("BROWSE")   // Menu option 1 (browse mode selected)
        || mode.equals ("VIEW")))   // Menu option 2
    {
      System.out.printf ("Unexpected mode2: [%s]%n", mode);
      return false;
    }

    int[] tabs1 = { 12, 25, 38, 47 };
    int[] tabs2 = { 12, 21, 31, 43 };

    field = screenFields.get (8);
    if (field.getFirstLocation () != 170)
      return false;
    String datasetName = field.getText ().trim ();
    currentDataset = datasetName;

    List<Field> headings = fieldManager.getRowFields (4);

    int screenType = 0;
    if (headings.size () == 10
        && fieldManager.textMatchesTrim (headings.get (5), "Created"))
      screenType = 1;
    else if (headings.size () == 13
        && fieldManager.textMatchesTrim (headings.get (5), "Init"))
      screenType = 2;
    else
      dumpFields (headings);

    if (screenType == 0)
      return false;

    for (int row = 5; row < screenDimensions.rows; row++)
    {
      List<Field> rowFields = fieldManager.getRowFields (row);
      if (rowFields.size () != 4 || rowFields.get (1).getText ().equals ("**End** "))
        break;

      String memberName = rowFields.get (1).getText ().trim ();
      Matcher matcher = memberNamePattern.matcher (memberName);
      if (!matcher.matches ())
      {
        System.out.printf ("Invalid member name: %s%n", memberName);
        break;
      }
      String details = rowFields.get (3).getText ();

      Dataset member = new Dataset (datasetName + "(" + memberName.trim () + ")");
      members.add (member);

      if (screenType == 1)
        screenType1 (member, details, tabs1);
      else if (screenType == 2)
        screenType2 (member, details, tabs2);
      else
        dumpFields (rowFields);
    }

    return true;
  }

  private void screenType1 (Dataset member, String details, int[] tabs)
  {
    String size = details.substring (0, tabs[0]);
    String created = details.substring (tabs[0], tabs[1]);
    String modified = details.substring (tabs[1], tabs[2]);
    String time = details.substring (tabs[2], tabs[3]);
    String id = details.substring (tabs[3]);

    member.setCreated (created.trim ());
    member.setReferred (modified.trim ());
    member.setCatalog (id.trim ());
    member.setExtents (getInteger ("Ext:", size.trim ()));
  }

  private void screenType2 (Dataset member, String details, int[] tabs)
  {
    String size = details.substring (0, tabs[0]);
    String init = details.substring (tabs[0], tabs[1]);
    String mod = details.substring (tabs[1], tabs[2]);
    String vvmm = details.substring (tabs[2], tabs[3]);
    String id = details.substring (tabs[3]);

    member.setCatalog (id.trim ());
    member.setExtents (getInteger ("Ext:", size.trim ()));
  }

  private int getInteger (String id, String value)
  {
    if (value == null || value.isEmpty () || value.equals ("?"))
      return 0;

    try
    {
      return Integer.parseInt (value);
    }
    catch (NumberFormatException e)
    {
      System.out.printf ("Error with %s: [%s]%n", id, value);
      return 0;
    }
  }

  private void checkSingleDataset (List<Field> fields)
  {
    if (fields.size () < 13)
      return;

    List<Field> rowFields = fieldManager.getRowFields (0, 3);
    if (rowFields.size () == 0)
      return;

    int fldNo = 0;
    for (Field field : rowFields)
    {
      if (field.getFirstLocation () % 80 == 1
          && (field.getDisplayLength () == 10 || field.getDisplayLength () == 9)
          && fldNo + 2 < rowFields.size ())
      {
        String text1 = field.getText ().trim ();
        String text2 = rowFields.get (fldNo + 1).getText ().trim ();
        String text3 = rowFields.get (fldNo + 2).getText ();
        //          System.out.println (text1);
        //          System.out.println (text2);
        //          System.out.println (text3);
        if ((text1.equals ("EDIT") || text1.equals ("VIEW") || text1.equals ("BROWSE"))
            && (text3.equals ("Columns") || text3.equals ("Line")))
        {
          int pos = text2.indexOf (' ');
          String datasetName = pos < 0 ? text2 : text2.substring (0, pos);
          String memberName = "";
          int pos1 = datasetName.indexOf ('(');
          if (pos1 > 0 && datasetName.endsWith (")"))
          {
            memberName = datasetName.substring (pos1 + 1, datasetName.length () - 1);
            datasetName = datasetName.substring (0, pos1);
          }
          Matcher matcher = datasetNamePattern.matcher (datasetName);
          if (matcher.matches ())
          {
            System.out.printf ("%-11s %-20s %s%n", text1, datasetName, memberName);
            singleDataset = datasetName;
            if (!memberName.isEmpty ())
              singleDataset += "(" + memberName + ")";
            //            Dataset dataset = new Dataset (singleDataset);
            if (!recentDatasets.contains (singleDataset))
              recentDatasets.add (singleDataset);
          }
        }
      }
      fldNo++;
    }
  }

  private boolean listMatchesArray (List<String> list, String[] array)
  {
    if (list.size () != array.length)
      return false;
    int i = 0;
    for (String text : list)
      if (!array[i++].equals (text))
        return false;
    return true;
  }

  private void dumpFields (List<Field> fields)
  {
    for (Field field : fields)
      System.out.println (field);
    System.out.println ("-------------------------");
  }

  @Override
  public String toString ()
  {
    StringBuilder text = new StringBuilder ();

    text.append ("Screen details:\n");
    text.append (String.format ("TSO screen ........ %s%n", isTSOCommandScreen));
    text.append (String.format ("Prompt field ...... %s%n", tsoCommandField));
    text.append (String.format ("Prompt line ....... %d%n", promptFieldLine));
    text.append (String.format ("Dataset list ...... %s%n", isDatasetList));
    text.append (String.format ("Members list ...... %s%n", isMemberList));
    text.append (String.format ("Current dataset ... %s%n", currentDataset));
    text.append (String.format ("Single dataset .... %s%n", singleDataset));
    text.append (String.format ("Userid/prefix ..... %s / %s%n", userid, prefix));
    text.append (String.format ("Datasets for ...... %s%n", datasetsMatching));
    text.append (String.format ("Volume ............ %s%n", datasetsOnVolume));
    text.append (String.format ("Datasets .......... %s%n",
                                datasets == null ? "" : datasets.size ()));
    text.append (String.format ("Recent datasets ... %s%n",
                                datasets == null ? "" : recentDatasets.size ()));
    int i = 0;
    for (String datasetName : recentDatasets)
      text.append (String.format ("            %3d ... %s%n", ++i, datasetName));

    return text.toString ();
  }
}