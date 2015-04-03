package edu.cmu.ml.proppr.learn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import edu.cmu.ml.proppr.examples.PosNegRWExample;
import edu.cmu.ml.proppr.graph.ArrayLearningGraph;
import edu.cmu.ml.proppr.graph.LearningGraph;
import edu.cmu.ml.proppr.learn.tools.LossData;
import edu.cmu.ml.proppr.learn.tools.LossData.LOSS;
import edu.cmu.ml.proppr.learn.tools.ReLUWeightingScheme;
import edu.cmu.ml.proppr.learn.tools.WeightingScheme;
import edu.cmu.ml.proppr.util.Dictionary;
import edu.cmu.ml.proppr.util.ParamVector;
import edu.cmu.ml.proppr.util.SRWOptions;
import edu.cmu.ml.proppr.util.SimpleParamVector;
import edu.cmu.ml.proppr.util.SymbolTable;
import gnu.trove.iterator.TIntDoubleIterator;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntDoubleMap;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.hash.TIntDoubleHashMap;

/**
 * Random walk learning
 * 
 * Flow of information:
 * 
 * 	 Train on example =
 *     load (initialize example parameters and compute M/dM)
 *     inference (compute p/dp)
 *     sgd (compute empirical loss gradient and apply to parameters)
 * 
 *   Accumulate gradient = 
 *     load  (initialize example parameters and compute M/dM)
 *     inference (compute p/dp)
 *     gradient (compute empirical loss gradient)
 * 
 * @author krivard
 *
 */
public class SRW {	
	private static final Logger log = Logger.getLogger(SRW.class);
	private static final double BOUND = 1.0e-15; //Prevent infinite log loss.
	private static Random random = new Random();
	public static void seed(long seed) { random.setSeed(seed); }
	public static WeightingScheme DEFAULT_WEIGHTING_SCHEME() { return new ReLUWeightingScheme(); }
	protected Set<String> untrainedFeatures;
	protected int epoch;
	protected SRWOptions c;
	protected LossData cumloss;
	public SRW() { this(new SRWOptions()); }
	public SRW(int maxT) { this(new SRWOptions(maxT)); }
	public SRW(SRWOptions params) {
		this.c = params;
		this.epoch = 1;
		this.untrainedFeatures = new TreeSet<String>();
		this.cumloss = new LossData();
	}

	/**
	 * Modify the parameter vector by taking a gradient step along the dir suggested by this example.
	 * @param params
	 * @param example
	 */
	public void trainOnExample(ParamVector params, PosNegRWExample example) {
		log.info("Training on "+example);
		SgdExample sgdex = wrapExample(example);

		initializeFeatures(params, sgdex.g);
		prepareForExample(params, sgdex.g, params);
		load(params, sgdex);
		inference(params, sgdex);
		sgd(params, sgdex);
	}

	public void accumulateGradient(ParamVector params, PosNegRWExample example, ParamVector accumulator) {
		log.info("Gradient calculating on "+example);
		SgdExample sgdex = wrapExample(example);

		initializeFeatures(params, sgdex.g);
		ParamVector<String,Double> prepare = new SimpleParamVector<String>();
		prepareForExample(params, sgdex.g, prepare);
		load(params, sgdex);
		inference(params, sgdex);
		TIntDoubleMap gradient = gradient(params,sgdex);
		
		for (Map.Entry<String, Double> e : prepare.entrySet()) {
			if (trainable(e.getKey())) 
				accumulator.adjustValue(e.getKey(), -e.getValue() / example.length());
		}
		for (TIntDoubleIterator it = gradient.iterator(); it.hasNext(); ) {
			it.advance();
			String feature = sgdex.g.featureLibrary.getSymbol(it.key());
			if (trainable(feature)) accumulator.adjustValue(sgdex.g.featureLibrary.getSymbol(it.key()), it.value() / example.length());
		}
	}

	protected SgdExample wrapExample(PosNegRWExample example) {
		return new SgdExample(example);
	}


	/** fills M, dM in ex **/
	protected void load(ParamVector params, SgdExample ex) {
		ex.M = new double[ex.g.node_hi][];
		ex.dM_lo = new int[ex.g.node_hi][];
		ex.dM_hi = new int[ex.g.node_hi][];
		// use compact extendible arrays here while we accumulate; convert to primitive array later
		TIntArrayList dM_features = new TIntArrayList();
		TDoubleArrayList dM_values = new TDoubleArrayList();
		for (int uid = 0; uid < ex.g.node_hi; uid++) {
			// (a); (b): initialization
			double tu = 0;
			TIntDoubleMap dtu = new TIntDoubleHashMap();
			int udeg = ex.g.node_near_hi[uid] - ex.g.node_near_lo[uid];
			double[] suv = new double[udeg];
			double[][] dfu = new double[udeg][];
			// begin (c): for each neighbor v of u,
			for(int eid = ex.g.node_near_lo[uid], xvi = 0; eid < ex.g.node_near_hi[uid]; eid++, xvi++) {
				int vid = ex.g.edge_dest[eid];
				// i. s_{uv} = w * phi_{uv}, a scalar:
				suv[xvi] = 0;
				for (int lid = ex.g.edge_labels_lo[eid]; lid < ex.g.edge_labels_hi[eid]; lid++) {
					suv[xvi] += params.get(ex.g.featureLibrary.getSymbol(ex.g.label_feature_id[lid])) * ex.g.label_feature_weight[lid];
				}
				// ii. t_u += f(s_{uv}), a scalar:
				tu += c.weightingScheme.edgeWeight(suv[xvi]);
				// iii. df_{uv} = f'(s_{uv})* phi_{uv}, a vector, as sparse as phi_{uv}
				// by looping over features i in phi_{uv}
				double [] dfuv = new double[ex.g.edge_labels_hi[eid] - ex.g.edge_labels_lo[eid]] ;
				double cee = c.weightingScheme.derivEdgeWeight(suv[xvi]);
				for (int lid = ex.g.edge_labels_lo[eid], dfuvi = 0; lid < ex.g.edge_labels_hi[eid]; lid++, dfuvi++) {
					// iii. again
					dfuv[dfuvi] = cee * ex.g.label_feature_weight[lid];
					// iv. dt_u += df_{uv}, a vector, as sparse as sum_{v'} phi_{uv'}
					// by looping over features i in df_{uv} 
					// (identical to features i in phi_{uv}, so we use the same loop)
					dtu.adjustOrPutValue(ex.g.label_feature_id[lid], dfuv[dfuvi], dfuv[dfuvi]);
				}
				dfu[xvi] = dfuv;
			}
			// end (c)

			// begin (d): for each neighbor v of u,
			ex.dM_lo[uid] = new int[udeg];
			ex.dM_hi[uid] = new int[udeg];
			ex.M[uid] = new double[udeg];
			double scale = (1 / (tu*tu));
			for(int eid = ex.g.node_near_lo[uid], xvi = 0; eid < ex.g.node_near_hi[uid]; eid++, xvi++) {
				int vid = ex.g.edge_dest[eid];
				ex.dM_lo[uid][xvi] = dM_features.size();
				// create the vector dM_{uv} = (1/t^2_u) * (t_u * df_{uv} - f(s_{uv}) * dt_u)
				// by looping over features i in dt_u
				
				// getting the df offset for features in dt_u is awkward, so we'll first iterate over features in df_uv,
				// then fill in the rest
				int[] seenFeatures = new int[ex.g.edge_labels_hi[eid] - ex.g.edge_labels_lo[eid]];
				for (int lid = ex.g.edge_labels_lo[eid], dfuvi = 0; lid < ex.g.edge_labels_hi[eid]; lid++, dfuvi++) {
					int fid = ex.g.label_feature_id[lid];
					dM_features.add(fid);
					double dMuvi = scale * (tu * dfu[xvi][dfuvi] - c.weightingScheme.edgeWeight(suv[xvi]) * dtu.get(fid));
					dM_values.add(dMuvi);
					seenFeatures[dfuvi] = fid; //save this feature so we can skip it later
				}
				Arrays.sort(seenFeatures);
				// we've hit all the features in df_uv, now we do the remaining features in dt_u:
				for (TIntDoubleIterator it = dtu.iterator(); it.hasNext(); ) {
					it.advance();
					// skip features we already added in the df_uv loop
					if (Arrays.binarySearch(seenFeatures, it.key())>=0) continue;
					dM_features.add(it.key());
					// zero the first term, since df_uv doesn't cover this feature
					double dMuvi = scale * ( - c.weightingScheme.edgeWeight(suv[xvi]) * it.value());
					dM_values.add(dMuvi);
				}
				ex.dM_hi[uid][xvi] = dM_features.size();
				// also create the scalar M_{uv} = f(s_{uv}) / t_u
				ex.M[uid][xvi] = (c.weightingScheme.edgeWeight(suv[xvi]) / tu);
			}
		}
		// discard extendible version in favor of primitive array
		ex.dM_feature_id = dM_features.toArray();
		ex.dM_value = dM_values.toArray();
	}

	/** adds new features to params vector @ 1% random perturbation */
	public void initializeFeatures(ParamVector params, LearningGraph graph) {
		for (String f : graph.getFeatureSet()) {
			if (!params.containsKey(f)) {
				params.put(f,c.weightingScheme.defaultWeight()+ (trainable(f) ? 0.01*random.nextDouble() : 0));
			}
		}
	}

	/** fills p, dp 
	 * @param params */
	protected void inference(ParamVector params, SgdExample ex) {
		ex.p = new double[ex.g.node_hi];
		ex.dp = new TIntDoubleMap[ex.g.node_hi];
		Arrays.fill(ex.p,0.0);
		// copy query into p
		for (TIntDoubleIterator it = ex.ex.getQueryVec().iterator(); it.hasNext(); ) {
			it.advance();
			ex.p[it.key()] = it.value();
		}
		for (int i=0; i<c.maxT; i++) {
			inferenceUpdate(ex);
		}

	}
	protected void inferenceUpdate(SgdExample ex) {
		double[] pNext = new double[ex.g.node_hi];
		TIntDoubleMap[] dNext = new TIntDoubleMap[ex.g.node_hi];
		// p: 2. for each node u
		for (int uid = 0; uid < ex.g.node_hi; uid++) {
			// p: 2(a) p_u^{t+1} += alpha * s_u
			pNext[uid] += c.apr.alpha * Dictionary.safeGet(ex.ex.getQueryVec(), uid, 0.0);
			// p: 2(b) for each neighbor v of u:
			for(int eid = ex.g.node_near_lo[uid], xvi = 0; eid < ex.g.node_near_hi[uid]; eid++, xvi++) {
				int vid = ex.g.edge_dest[eid];
				// p: 2(b)i. p_v^{t+1} += (1-alpha) * p_u^t * M_uv
				pNext[vid] += (1-c.apr.alpha) * ex.p[uid] * ex.M[uid][xvi];
				// d: i. for each feature i in dM_uv:
				if (dNext[vid] == null)
					dNext[vid] = new TIntDoubleHashMap(ex.dM_hi[uid][xvi] - ex.dM_lo[uid][xvi]);
				for (int dmi = ex.dM_lo[uid][xvi]; dmi < ex.dM_hi[uid][xvi]; dmi++) {
					// d_vi^{t+1} += (1-alpha) * p_u^{t} * dM_uvi
					if (ex.dM_value[dmi]==0) continue;
					double inc = (1-c.apr.alpha) * ex.p[uid] * ex.dM_value[dmi];
					dNext[vid].adjustOrPutValue(ex.dM_feature_id[dmi], inc, inc);
				}
				// d: ii. for each feature i in d_u^t
				if (ex.dp[uid] == null) continue; // skip when d is empty
				for (TIntDoubleIterator it = ex.dp[uid].iterator(); it.hasNext();) {
					it.advance();
					if (it.value()==0) continue;
					// d_vi^{t+1} += (1-alpha) * d_ui^t * M_uv
					double inc = (1-c.apr.alpha) * it.value() * ex.M[uid][xvi];
					dNext[vid].adjustOrPutValue(it.key(),inc,inc);
				}
			}
		}
		
		// sanity check on p
		if (log.isDebugEnabled()) {
			double sum = 0;
			for (double d : pNext) sum += d;
			if (Math.abs(sum - 1.0) > c.apr.epsilon)
				log.error("invalid p computed: "+sum);
		}
		ex.p = pNext;
		ex.dp = dNext;
	}

	/** edits params */
	protected void sgd(ParamVector params, SgdExample ex) {
		TIntDoubleMap gradient = gradient(params,ex);
		// apply gradient to param vector
		for (TIntDoubleIterator grad = gradient.iterator(); grad.hasNext(); ) {
			grad.advance();
			if (grad.value()==0) continue;
			String feature = ex.g.featureLibrary.getSymbol(grad.key());
			if (trainable(feature)) params.adjustValue(feature, - learningRate() * grad.value());
		}
	}

	protected TIntDoubleMap gradient(ParamVector params, SgdExample ex) {
		Set<String> features = this.localFeatures(params, ex.g);
		TIntDoubleMap gradient = new TIntDoubleHashMap(features.size());
		// add regularization term
		regularization(params, ex, gradient);
		
		int nonzero=0;
		double mag = 0;
		
		// add empirical loss gradient term
		// positive examples
		double pmax = 0;
		for (int a : ex.ex.getPosList()) {
			double pa = clip(ex.p[a]);
			if(pa > pmax) pmax = pa;
			for (TIntDoubleIterator da = ex.dp[a].iterator(); da.hasNext(); ) {
				da.advance();
				if (da.value()==0) continue;
				nonzero++;
				double aterm = -da.value() / pa;
				mag += aterm*aterm;
				gradient.adjustOrPutValue(da.key(), aterm, aterm);
			}
			if (log.isDebugEnabled()) log.debug("+p="+pa);
			this.cumloss.add(LOSS.LOG, -Math.log(pa));
		}

		//negative instance booster
		double h = pmax + c.delta;
		double beta = 1;
		if(c.delta < 0.5) beta = (Math.log(1/h))/(Math.log(1/(1-h)));

		// negative examples
		for (int b : ex.ex.getNegList()) {
			double pb = clip(ex.p[b]);
			for (TIntDoubleIterator db = ex.dp[b].iterator(); db.hasNext(); ) {
				db.advance();
				if (db.value()==0) continue;
				nonzero++;
				double bterm = beta * db.value() / (1 - pb);
				mag += bterm*bterm;
				gradient.adjustOrPutValue(db.key(), bterm, bterm);
			}
			if (log.isDebugEnabled()) log.debug("-p="+pb);
			this.cumloss.add(LOSS.LOG, -Math.log(1.0-pb));
		}

//		log.info("gradient step magnitude "+Math.sqrt(mag)+" "+ex.ex.toString());
		if (nonzero==0) log.warn("0 gradient. Try another weighting scheme? "+ex.ex.toString());
		return gradient;
	}
	
	/** template: update gradient with regularization term */
	protected void regularization(ParamVector params, SgdExample ex, TIntDoubleMap gradient) {}


	static class SgdExample {
		PosNegRWExample ex;
		ArrayLearningGraph g;

		// length = sum(nodes i) (degree of i) = #edges
		double[][] M;

		// length = sum(edges e) (# features on e) = #feature assignments
		int[] dM_feature_id;
		double[] dM_value;
		// length = sum(nodes i) degree of i = #edges
		int[][] dM_lo;
		int[][] dM_hi;

		// p[u] = value
		double[] p;
		// dp[u].put(fid, value)
		TIntDoubleMap[] dp;

		public SgdExample(PosNegRWExample example) {
			this.ex = example;
			if (! (example.getGraph() instanceof ArrayLearningGraph))
				throw new IllegalStateException("Revised SRW requires ArrayLearningGraph in streamed examples. Run with --graphClass ArrayLearningGraph.");
			this.g = (ArrayLearningGraph) example.getGraph();
		}

		public int getFeatureId(String f) {
			return g.featureLibrary.getId(f);
		}
	}


	//////////////////////////// copypasta from SRW.java:

	public static HashMap<String,List<String>> constructAffinity(File affgraph){
		if (affgraph == null) throw new IllegalArgumentException("Missing affgraph file!");
		//Construct the affinity matrix from the input
		BufferedReader reader;
		try {
			reader = new BufferedReader(new FileReader(affgraph));
			HashMap<String,List<String>> affinity = new HashMap<String,List<String>>();
			String line = null;
			while ((line = reader.readLine()) != null) {
				String[] items = line.split("\\t");
				if(!affinity.containsKey(items[0])){
					List<String> pairs = new ArrayList<String>();
					pairs.add(items[1]);
					affinity.put(items[0], pairs);
				}
				else{
					List<String> pairs = affinity.get(items[0]);
					pairs.add(items[1]);
					affinity.put(items[0], pairs);
				}
			}
			reader.close();
			return affinity;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	public static HashMap<String,Integer> constructDegree(Map<String,List<String>> affinity){
		HashMap<String,Integer> diagonalDegree = new HashMap<String,Integer>();
		for (String key : affinity.keySet()) {
			diagonalDegree.put(key, affinity.get(key).size());
		}
		if (log.isDebugEnabled()) log.debug("d size:" + diagonalDegree.size());
		return diagonalDegree;
	}


	protected double learningRate() {
		return Math.pow(this.epoch,-2) * c.eta;
	}

	public double clip(double prob)
	{
		if(prob <= 0) return BOUND;
		return prob;
	}

	public boolean trainable(String feature) {
		return !untrainedFeatures.contains(feature);
	}

	/** Allow subclasses to filter feature list **/
	public Set<String> localFeatures(ParamVector<String,?> paramVec, LearningGraph graph) {
		return paramVec.keySet();
	}
	/** Allow subclasses to swap in an alternate parameter implementation **/
	public ParamVector<String,?> setupParams(ParamVector<String,?> params) { return params; }


	/** Allow subclasses to do pre-example calculations (e.g. lazy regularization) **/
	public void prepareForExample(ParamVector params, LearningGraph graph, ParamVector apply) {}
	
	/** Allow subclasses to do additional parameter processing at the end of an epoch **/
	public void cleanupParams(ParamVector<String,?> params, ParamVector apply) {}


	public Set<String> untrainedFeatures() { return this.untrainedFeatures; }
	public WeightingScheme getWeightingScheme() {
		return c.weightingScheme;
	}
	public void setEpoch(int e) {
		this.epoch = e;
	}
	public void clearLoss() {
		this.cumloss.clear();
	}
	public LossData cumulativeLoss() {
		return this.cumloss.copy();
	}
	public void setWeightingScheme(WeightingScheme newWeightingScheme) {
		c.weightingScheme = newWeightingScheme;
	}
	public SRWOptions getOptions() {
		return c;
	}
	public void setAlpha(double d) {
		c.apr.alpha = d;
	}
	public void setMu(double d) {
		c.mu = d;
	}
}
