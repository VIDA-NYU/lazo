package lazo.benchmark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import lazo.index.LazoIndex;
import lazo.index.LazoIndex.LazoCandidate;
import lazo.sketch.LazoSketch;
import lazo.sketch.Sketch;
import lazo.sketch.SketchType;

public class LazoBenchmarkSingleCol {

    // metrics
    private long io_time;
    private long index_time;
    private long query_time;
    private long ech_time;
    private long post_time;
    private int corrections;
    private float magChange;
    private int js_impact_corrections;
    private int jcx_impact_corrections;

    private CsvParser parser;
    private Map<Integer, String> hashIdToName;

    private Map<String, File> nameToFile = new HashMap<>();

    public LazoBenchmarkSingleCol() {
	// csv parser
	CsvParserSettings settings = new CsvParserSettings();
	settings.getFormat().setLineSeparator("\n");
	this.parser = new CsvParser(settings);

	// id, names, etc
	this.hashIdToName = new HashMap<>();

    }

    private File[] enumerateFiles(String path) {
	File folder = new File(path);
	File[] files = folder.listFiles();
	return files;
    }

    private int hashName(String fileName, String columnName) {
	return (fileName + columnName).hashCode();
    }

    public Reader getReader(File file) throws FileNotFoundException {
	FileReader fr = new FileReader(file);
	BufferedReader br = new BufferedReader(fr);
	return br;
    }

    int failed = 0;
    List<String> failedFiles = new ArrayList<>();

    public Map<Integer, Set<String>> obtainColumns(File file) {
	long s = System.currentTimeMillis();
	Map<Integer, Set<String>> tableSets = new HashMap<>();
	Map<Integer, Integer> indexToHashId = new HashMap<>();

	List<String[]> allRows = null;
	try {
	    allRows = parser.parseAll(getReader(file));
	    // System.out.println(allRows);
	}
	// catch (FileNotFoundException e) {
	// e.printStackTrace();
	// }
	catch (Exception ex) {
	    String absPath = file.getAbsolutePath();
	    failedFiles.add(absPath);
	    this.failed += 1;
	    ex.printStackTrace();
	    return null;
	}
	try {
	    String[] header = allRows.get(0);
	    int idx = 0;
	    for (String columnName : header) {
		int id = hashName(file.getName(), columnName);
		tableSets.put(id, new HashSet<>());
		indexToHashId.put(idx, id);
		this.hashIdToName.put(id, file.getName() + "->" + columnName);
		idx++;
	    }
	    for (int i = 1; i < allRows.size(); i++) {
		String[] row = allRows.get(i);
		for (int j = 0; j < row.length; j++) {
		    // add value to correct column
		    tableSets.get(indexToHashId.get(j)).add(row[j]);
		}
	    }
	} catch (Exception npe) {
	    String absPath = file.getAbsolutePath();
	    failedFiles.add(absPath);
	    this.failed += 1;
	    npe.printStackTrace();
	    return null;
	}
	long e = System.currentTimeMillis();
	this.io_time += (e - s);
	return tableSets;
    }

    private Map<Integer, Set<String>> getTable(String table, Map<String, Map<Integer, Set<String>>> cache) {
	if (cache.containsKey(table)) {
	    return cache.get(table);
	}
	File f = this.nameToFile.get(table);
	Map<Integer, Set<String>> cols = this.obtainColumns(f);
	cache.put(table, cols);
	return cols;
    }

    public Set<Pair<Integer, Integer>> postProcessing(Set<Pair<Integer, Integer>> candidates, float threshold) {
	Set<Pair<Integer, Integer>> verifiedPairs = new HashSet<>();
	Map<String, Map<Integer, Set<String>>> cache = new HashMap<>();
	for (Pair<Integer, Integer> candidate : candidates) {
	    String fullName1 = this.hashIdToName.get(candidate.x);
	    String tokens1[] = fullName1.split("->");
	    String tableName1 = tokens1[0];
	    Map<Integer, Set<String>> tableX = this.getTable(tableName1, cache);
	    String fullName2 = this.hashIdToName.get(candidate.y);
	    String tokens2[] = fullName2.split("->");
	    String tableName2 = tokens2[0];
	    Map<Integer, Set<String>> tableY = this.getTable(tableName2, cache);
	    float realJS = Utils.computeJS(tableX.get(candidate.x), tableY.get(candidate.y));
	    if (realJS >= threshold) {
		verifiedPairs.add(candidate);
	    }
	}
	return verifiedPairs;
    }

    private Set<String> readColumnFile(File f) {
	Set<String> strings = new HashSet<>();
	BufferedReader br;
	try {
	    br = new BufferedReader(new FileReader(f));
	    String line = null;
	    while ((line = br.readLine()) != null) {
		strings.add(line);
	    }
	} catch (FileNotFoundException e) {
	    e.printStackTrace();
	} catch (IOException e) {
	    e.printStackTrace();
	}
	return strings;
    }

    public Set<Pair<Integer, Integer>> computeAllPairs(File[] files, float threshold, int k) {
	Set<Pair<Integer, Integer>> similarPairs = new HashSet<>();
	// LazoIndexBase index = new LazoIndexBase(k);
	LazoIndex index = new LazoIndex(k);
	// Create sketches and index
	Map<Integer, Sketch> idToSketch = new HashMap<>();
	for (int i = 0; i < files.length; i++) {
	    System.out.println("Processing: " + i + "/" + files.length);
	    System.out.println(files[i].getAbsolutePath());

	    // Read file
	    Set<String> col = readColumnFile(files[i]);
	    // Map<Integer, Set<String>> table = obtainColumns(files[i]);
	    if (col == null) {
		continue; // table is broken
	    }

	    long s = System.currentTimeMillis();
	    int id = files[i].getName().hashCode();
	    LazoSketch ls = new LazoSketch(k, SketchType.MINHASH);

	    boolean valid = false;
	    for (String value : col) {
		if (value != null) {
		    ls.update(value);
		    valid = true;
		}
	    }
	    if (valid) {
		index.insert(id, ls);
		idToSketch.put(id, ls);
	    }
	    long e = System.currentTimeMillis();
	    this.index_time += (e - s);
	}
	// Query to retrieve pairs
	long s = System.currentTimeMillis();
	for (Entry<Integer, Sketch> e : idToSketch.entrySet()) {
	    int id = e.getKey();
	    LazoSketch mh = (LazoSketch) e.getValue();
	    Set<LazoCandidate> candidates = index.query(mh, threshold, 0f);
	    for (LazoCandidate o : candidates) {
		if (id != (int) o.key) {
		    similarPairs.add(new Pair<Integer, Integer>(id, (int) o.key));
		}
	    }
	}
	long e = System.currentTimeMillis();
	this.query_time = (e - s);
	this.ech_time = index.get_ech_time();
	this.corrections = index.corrections;
	this.magChange = index.magnitude_correction;
	this.js_impact_corrections = index.js_impactful_corrections;
	this.jcx_impact_corrections = index.jcx_impactful_corrections;
	return similarPairs;
    }

    public static void main(String args[]) {

	LazoBenchmarkSingleCol mls = new LazoBenchmarkSingleCol();

	if (args.length < 4) {
	    System.out.println("Usage: <inputPath> <outputPath> <similarityThreshold> <minhash-permutations>");
	}

	String inputPath = args[0];
	String outputPath = args[1];
	float similarityThreshold = Float.parseFloat(args[2]);
	int k = Integer.parseInt(args[3]);

	File[] filesInPath = mls.enumerateFiles(inputPath);
	for (File f : filesInPath) {
	    mls.nameToFile.put(f.getName(), f);
	}

	System.out.println("Found " + filesInPath.length + " files to process");
	long start = System.currentTimeMillis();
	Set<Pair<Integer, Integer>> output = mls.computeAllPairs(filesInPath, similarityThreshold, k);
	long end = System.currentTimeMillis();

	long s = System.currentTimeMillis();
	// Set<Pair<Integer, Integer>> cleanOutput = mls.postProcessing(output,
	// similarityThreshold);
	long e = System.currentTimeMillis();
	mls.post_time = (e - s);

	for (Pair<Integer, Integer> pair : output) {
	    int xid = pair.x;
	    int yid = pair.y;
	    String xname = mls.hashIdToName.get(xid);
	    String yname = mls.hashIdToName.get(yid);
	    System.out.println(xname + " ~= " + yname);
	}
	System.out.println("Total time: " + (end - start));
	System.out.println("Total failed tasks: " + mls.failed);
	System.out.println("io time: " + (mls.io_time));
	System.out.println("index time: " + (mls.index_time));
	System.out.println("query time: " + (mls.query_time));
	System.out.println("ech time (part of query time): " + (mls.ech_time));
	System.out.println("post time: " + mls.post_time);
	System.out.println("Total sim candidates: " + output.size());
	// System.out.println("Total sim pairs: " + cleanOutput.size());
	System.out.println("#corrections: " + mls.corrections);
	System.out.println("#js_impact_corrections: " + mls.js_impact_corrections);
	System.out.println("#jcx_impact_corrections: " + mls.jcx_impact_corrections);
	System.out.println("#magChange: " + mls.magChange);

	// Write output in format x,y for all pairs
	File f = new File(outputPath);
	BufferedWriter bw = null;
	try {
	    bw = new BufferedWriter(new FileWriter(f));
	    for (Pair<Integer, Integer> pair : output) {
		int xid = pair.x;
		int yid = pair.y;
		String line = xid + "," + yid + '\n';
		bw.write(line);
	    }
	    bw.flush();
	    bw.close();
	} catch (IOException eio) {
	    // TODO Auto-generated catch block
	    eio.printStackTrace();
	}
	System.out.println("Results output to: " + outputPath);

	File f1 = new File(outputPath + ".ERRORS.TXT");
	BufferedWriter bw1 = null;
	try {
	    bw1 = new BufferedWriter(new FileWriter(f1));
	    for (String line : mls.failedFiles) {
		bw1.write(line + '\n');
	    }
	    bw1.flush();
	    bw1.close();
	} catch (IOException eio) {
	    // TODO Auto-generated catch block
	    eio.printStackTrace();
	}
	System.out.println("ERRORS file output to: " + outputPath + ".ERRORS.TXT");

    }

}
