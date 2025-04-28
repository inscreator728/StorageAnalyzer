import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.zip.*;

public class StorageAnalyzer extends JFrame {
    private JComboBox<String> driveCombo;
    private JRadioButton driveScanRadio, folderScanRadio;
    private JButton browseFolderBtn, scanBtn, compressBtn;
    private JTable resultTable;
    private FolderTableModel tableModel;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JFileChooser folderChooser;
    private File selectedFolder;
    private List<Path> itemsToCompress = new ArrayList<>();

    public StorageAnalyzer() {
        super("Storage Analyzer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        initUI();
    }

    private void initUI() {
        // Top panel: scan options
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        driveScanRadio = new JRadioButton("Drive Scan", true);
        folderScanRadio = new JRadioButton("Folder Scan");
        ButtonGroup group = new ButtonGroup();
        group.add(driveScanRadio);
        group.add(folderScanRadio);
        top.add(driveScanRadio);
        top.add(folderScanRadio);

        driveCombo = new JComboBox<>();
        for (File root : File.listRoots()) {
            driveCombo.addItem(root.getAbsolutePath());
        }
        top.add(new JLabel("Select Drive:"));
        top.add(driveCombo);

        browseFolderBtn = new JButton("Browse Folder...");
        browseFolderBtn.setEnabled(false);
        top.add(browseFolderBtn);

        scanBtn = new JButton("Scan Large Folders");
        top.add(scanBtn);

        // Compress button
        compressBtn = new JButton("Select & Compress");
        top.add(compressBtn);

        // Table model
        tableModel = new FolderTableModel();
        resultTable = new JTable(tableModel);
        JScrollPane tableScroll = new JScrollPane(resultTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Top Large Folders"));

        // Progress and logs
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        logArea = new JTextArea(8, 80);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));

        // Layout
        Container c = getContentPane();
        c.setLayout(new BorderLayout(10, 10));
        c.add(top, BorderLayout.NORTH);
        c.add(tableScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.add(progressBar, BorderLayout.NORTH);
        bottom.add(logScroll, BorderLayout.CENTER);
        c.add(bottom, BorderLayout.SOUTH);

        // Folder chooser
        folderChooser = new JFileChooser();
        folderChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        // Listeners
        driveScanRadio.addActionListener(e -> toggleScanMode());
        folderScanRadio.addActionListener(e -> toggleScanMode());
        browseFolderBtn.addActionListener(e -> chooseFolder());
        scanBtn.addActionListener(e -> startScan());
        compressBtn.addActionListener(e -> startCompress());
    }

    private void toggleScanMode() {
        boolean folderMode = folderScanRadio.isSelected();
        driveCombo.setEnabled(!folderMode);
        browseFolderBtn.setEnabled(folderMode);
    }

    private void chooseFolder() {
        if (folderChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFolder = folderChooser.getSelectedFile();
            log("Selected folder: " + selectedFolder.getAbsolutePath());
        }
    }

    private void startScan() {
        scanBtn.setEnabled(false);
        tableModel.clear();
        logArea.setText("");

        SwingWorker<Void, FolderRecord> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<Path> roots = new ArrayList<>();
                if (folderScanRadio.isSelected() && selectedFolder != null) {
                    roots.add(selectedFolder.toPath());
                } else {
                    roots.add(Paths.get((String) driveCombo.getSelectedItem()));
                }

                for (Path base : roots) {
                    // scan immediate child folders
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
                        for (Path p : ds) {
                            if (Files.isDirectory(p)) {
                                long size = folderSize(p);
                                publish(new FolderRecord(p, size));
                            }
                        }
                    }
                }
                return null;
            }

            @Override
            protected void process(List<FolderRecord> chunks) {
                for (FolderRecord fr : chunks) {
                    tableModel.addRecord(fr);
                }
            }

            @Override
            protected void done() {
                scanBtn.setEnabled(true);
                tableModel.sortDescending();
                log("Scan complete: found " + tableModel.getRowCount() + " folders.");
            }
        };
        worker.execute();
    }

    private long folderSize(Path folder) {
        final long[] total = { 0 };
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log("Error scanning " + folder + ": " + e.getMessage());
        }
        return total[0];
    }

    private void startCompress() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            itemsToCompress.clear();
            for (File f : chooser.getSelectedFiles()) {
                itemsToCompress.add(f.toPath());
                log("Selected for compression: " + f.getAbsolutePath());
            }
            // choose destination
            JFileChooser save = new JFileChooser();
            save.setDialogTitle("Save ZIP As");
            if (save.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File zipFile = save.getSelectedFile();
                compressBtn.setEnabled(false);
                SwingWorker<Void, String> zipWorker = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                            int total = itemsToCompress.size();
                            for (int i = 0; i < total; i++) {
                                Path path = itemsToCompress.get(i);
                                addToZip(zos, path, path.getFileName().toString());
                                int prog = (int) ((i + 1) * 100.0 / total);
                                setProgress(prog);
                                publish("Compressed: " + path);
                            }
                        }
                        return null;
                    }

                    @Override
                    protected void process(List<String> msgs) {
                        for (String m : msgs)
                            log(m);
                    }

                    @Override
                    protected void done() {
                        compressBtn.setEnabled(true);
                        log("Compression complete: " + zipFile.getAbsolutePath());
                    }
                };
                zipWorker.addPropertyChangeListener(evt -> {
                    if ("progress".equals(evt.getPropertyName())) {
                        progressBar.setValue((Integer) evt.getNewValue());
                    }
                });
                zipWorker.execute();
            }
        }
    }

    private void addToZip(ZipOutputStream zos, Path path, String entryName) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path child : ds) {
                    addToZip(zos, child, entryName + "/" + child.getFileName());
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(path, zos);
            zos.closeEntry();
        }
    }

    private void log(String msg) {
        logArea.append(msg + "\n");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StorageAnalyzer().setVisible(true));
    }

    // Inner classes
    private static class FolderRecord {
        Path folder;
        long size;

        FolderRecord(Path f, long s) {
            folder = f;
            size = s;
        }
    }

    private static class FolderTableModel extends AbstractTableModel {
        private final String[] cols = { "Path", "Size (GB)" };
        private final List<FolderRecord> data = new ArrayList<>();

        void addRecord(FolderRecord r) {
            data.add(r);
            fireTableRowsInserted(data.size() - 1, data.size() - 1);
        }

        void clear() {
            int n = data.size();
            data.clear();
            if (n > 0)
                fireTableRowsDeleted(0, n - 1);
        }

        void sortDescending() {
            data.sort((a, b) -> Long.compare(b.size, a.size));
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            FolderRecord fr = data.get(r);
            switch (c) {
                case 0:
                    return fr.folder.toAbsolutePath().toString();
                case 1:
                    return String.format("%.2f", fr.size / 1024.0 / 1024.0 / 1024.0);
                default:
                    return null;
            }
        }
    }
}
