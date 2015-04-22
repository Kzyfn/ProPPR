package edu.cmu.ml.proppr.graph;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.procedure.TIntProcedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;


public abstract class LearningGraph {
	private static final Logger log = Logger.getLogger(LearningGraph.class);
	public static class GraphFormatException extends Exception {
		public GraphFormatException(String msg) { super(msg); }
	}
	public abstract Set<String> getFeatureSet();
	public abstract int[] getNodes();
	public abstract int nodeSize();
	public abstract int edgeSize();
	public abstract void serialize(StringBuilder serialized);
}
