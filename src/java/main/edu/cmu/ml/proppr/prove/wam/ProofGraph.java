package edu.cmu.ml.proppr.prove.wam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import edu.cmu.ml.proppr.examples.GroundedExample;
import edu.cmu.ml.proppr.examples.InferenceExample;
import edu.cmu.ml.proppr.graph.LightweightStateGraph;
import edu.cmu.ml.proppr.prove.wam.plugins.WamPlugin;
import edu.cmu.ml.proppr.prove.wam.plugins.builtin.FilterPluginCollection;
import edu.cmu.ml.proppr.prove.wam.plugins.builtin.PluginFunction;
import edu.cmu.ml.proppr.util.APROptions;
import gnu.trove.strategy.HashingStrategy;

/**
 * # Creates the graph defined by a query, a wam program, and a list of
# WamPlugins.
 * @author "William Cohen <wcohen@cs.cmu.edu>"
 * @author "Kathryn Mazaitis <krivard@cs.cmu.edu>"
 *
 */
public class ProofGraph {
	private static final Logger log = Logger.getLogger(ProofGraph.class);
	public static final boolean DEFAULT_RESTART = false;
	public static final boolean DEFAULT_TRUELOOP = true;
	public static final Goal TRUELOOP = new Goal("id",new ConstantArgument("trueLoop"));
	public static final Goal TRUELOOP_RESTART = new Goal("id",new ConstantArgument("trueLoopRestart"));
	public static final Goal RESTART = new Goal("id",new ConstantArgument("restart"));
	public static final Goal ALPHABOOSTER = new Goal("id",new ConstantArgument("alphaBooster"));
	
	private InferenceExample example;
	private WamProgram program;
	private final WamInterpreter interpreter;
	private int queryStartAddress;
	private final ImmutableState startState;
	private int[] variableIds;
	private LightweightStateGraph graph;
	private Map<Goal,Double> trueLoopFD;
	private Map<Goal,Double> trueLoopRestartFD;
	private Goal restartFeature;
	private Goal restartBoosterFeature;
	private APROptions apr;
	public ProofGraph(Query query, APROptions apr, WamProgram program, WamPlugin ... plugins) throws LogicProgramException {
		this(new InferenceExample(query,null,null),apr,program,plugins);
	}
	public ProofGraph(InferenceExample ex, APROptions apr, WamProgram program, WamPlugin ... plugins) throws LogicProgramException {
		this.example = ex; 
		this.apr = apr;
		this.program = new WamQueryProgram(program);
		WamPlugin[] fullPluginList = addBuiltinPlugins(plugins);
		this.interpreter = new WamInterpreter(this.program, fullPluginList);
		this.startState = this.createStartState();
		
		this.trueLoopFD = new HashMap<Goal,Double>(); this.trueLoopFD.put(TRUELOOP,1.0);
		this.trueLoopRestartFD = new HashMap<Goal,Double>(); this.trueLoopRestartFD.put(TRUELOOP_RESTART,1.0);
		this.restartFeature = RESTART;
		this.restartBoosterFeature = ALPHABOOSTER;
		this.graph = new LightweightStateGraph(new HashingStrategy<State>() {
			@Override
			public int computeHashCode(State s) {
				return s.canonicalHash();
			}

			@Override
			public boolean equals(State s1, State s2) {
				return s1.canonicalHash() == s2.canonicalHash();
			}});
	}
	private ImmutableState createStartState() throws LogicProgramException {
		// execute to the first call
		this.example.getQuery().variabilize();
		// discard any compiled code added by previous queries
		this.program.revert();
		this.queryStartAddress = program.size();
		// add the query on to the end of the program
		this.program.append(this.example.getQuery());
		// execute querycode to get start state
		Map<Goal,Double> features = this.interpreter.executeWithoutBranching(queryStartAddress);
		if (!features.isEmpty()) throw new LogicProgramException("should be a call");
		if (interpreter.getState().isFailed()) throw new LogicProgramException("query shouldn't have failed");
		// remember variable IDs
		State s = interpreter.saveState();
		this.variableIds = new int[s.getHeapSize()];
		int v=1;
		for (int i=0; i<variableIds.length; i++) {
			if (s.hasConstantAt(i)) variableIds[i] = 0;
			else variableIds[i] = -v++;
		}
		ImmutableState result = interpreter.saveState();
		result.setCanonicalHash(this.interpreter, result);
		return result;
	}
	private WamPlugin[] addBuiltinPlugins(WamPlugin ... plugins) {
		WamPlugin[] result = Arrays.copyOf(plugins,plugins.length+1);
		FilterPluginCollection filters = new FilterPluginCollection(this.apr);
		result[plugins.length] = filters;
		filters.register("neq/2", new PluginFunction(){
			@Override
			public boolean run(WamInterpreter wamInterp) throws LogicProgramException {
				String arg1 = wamInterp.getConstantArg(2,1);
				String arg2 = wamInterp.getConstantArg(2,2);
				if (arg1==null || arg2==null) throw new LogicProgramException("cannot call neq/2 unless both variables are bound");
				return arg1!=arg2;
			}});
		return result;
	}
	
	/* **************** proving ****************** */
	
	/**
	 * Return the list of outlinks from the provided state, including a reset outlink back to the query.
	 * @param state
	 * @param trueLoop
	 * @return
	 * @throws LogicProgramException
	 */
	public List<Outlink> pgOutlinks(State state, boolean trueLoop) throws LogicProgramException {
		if (!this.graph.outlinksDefined(state)) {
			List<Outlink> outlinks = this.computeOutlinks(state,trueLoop);
			Map<Goal,Double> restartFD = new HashMap<Goal,Double>();
			restartFD.put(this.restartFeature,1.0);
			outlinks.add(new Outlink(restartFD, this.startState));
			if (log.isDebugEnabled()) {
				// check for duplicate hashes
				Set<Integer> canons = new TreeSet<Integer>();
				for (Outlink o : outlinks) {
					if (canons.contains(o.child.canon)) log.warn("Duplicate canonical hash found in outlinks of state "+state);
					canons.add(o.child.canon);
				}
			}
			this.graph.setOutlinks(state,outlinks);
			return outlinks;
		}
		return this.graph.getOutlinks(state);
	}
	private List<Outlink> computeOutlinks(State state, boolean trueLoop) throws LogicProgramException {
		List<Outlink> result = new ArrayList<Outlink>();
		if (state.isCompleted()) {
			if (trueLoop) {
				result.add(new Outlink(this.trueLoopFD, state));
			}
		} else if (!state.isFailed()) {
			result = this.interpreter.wamOutlinks(state);
		}
		
		// generate canonical versions of each state
		for (Outlink o : result) {
			o.child.setCanonicalHash(this.interpreter, this.startState);
		}
		return result;
	}
	/** The number of outlinks for a state, including the reset outlink back to the query. 
	 * @throws LogicProgramException */
	public int pgDegree(State state) throws LogicProgramException {
		return this.pgDegree(state, true);
	}
	
	public int pgDegree(State state, boolean trueLoop) throws LogicProgramException {
		return this.pgOutlinks(state, trueLoop).size();
	}
	
	/* ***************************** grounding ******************* */
	
	public int asId(State s) {
		return this.graph.getId(s);
	}

	public Map<Argument,String> asDict(State s) {
		Map<Argument,String> result = new HashMap<Argument,String>();
		List<String> constants = this.interpreter.getConstantTable().getSymbolList();
		for (int k : s.getRegisters()) {
			int j = s.dereference(k);
			if (s.hasConstantAt(j)) result.put(new VariableArgument(j<this.variableIds.length ? this.variableIds[j] : k), constants.get(s.getIdOfConstantAt(j)-1));
			else result.put(new VariableArgument(-k), "X"+j);
		}
		return result;
	}
	
	/** Get a copy of the query represented by this proof using the variable bindings from
	 * the specified state.
	 * @param state
	 * @return
	 */
	public Query fill(State state) {
		Goal[] oldRhs = this.example.getQuery().getRhs();
		Goal[] newRhs = new Goal[oldRhs.length];
		Map<Argument,String> values = asDict(state);
		for (int i=0; i<oldRhs.length; i++) {
			newRhs[i] = fillGoal(oldRhs[i], values);
		}
		return new Query(newRhs);
	}
	
	private Goal fillGoal(Goal g, Map<Argument,String> values) {
		return new Goal(g.getFunctor(), fillArgs(g.getArgs(), values));
	}
	private Argument[] fillArgs(Argument[] args, Map<Argument,String> values) {
		Argument[] ret = new Argument[args.length];
		for(int i=0; i<args.length; i++) {
			if (values.containsKey(args[i])) ret[i] = new ConstantArgument(values.get(args[i]));
			else ret[i] = args[i];
		}
		return ret;
	}

	public GroundedExample makeRWExample(Map<State, Double> ans) {
		List<State> posIds = new ArrayList<State>();
		List<State> negIds = new ArrayList<State>();
		for (Map.Entry<State,Double> soln : ans.entrySet()) {
			if (soln.getKey().isCompleted()) {
				Query ground = fill(soln.getKey());
				// FIXME: slow?
				if (Arrays.binarySearch(example.getPosSet(), ground) >= 0) posIds.add(soln.getKey());
				if (Arrays.binarySearch(example.getNegSet(), ground) >= 0) negIds.add(soln.getKey());
			}
		}
		Map<State,Double> queryVector = new HashMap<State,Double>();
		queryVector.put(this.startState, 1.0);
		return new GroundedExample(this.graph, queryVector, posIds, negIds);
	}
	/* ************************** de/serialization *********************** */
	
	public String serialize(GroundedExample x) {
		StringBuilder line = new StringBuilder();
		line.append(this.example.getQuery().toString())
		.append("\t");
		appendNodes(x.getQueryVec().keySet(), line);
		line.append("\t");
		appendNodes(x.getPosList(), line);
		line.append("\t");
		appendNodes(x.getNegList(), line);
		line.append("\t")
		.append(x.getGraph().toString())
		.append("\n");
		return line.toString();
	}
	
	private void appendNodes(Iterable<State> group, StringBuilder line) {
		boolean first=true;
		for (State q : group) {
			if (first) first=false;
			else line.append(",");
			line.append(this.graph.getId(q));
		}
	}
	
	/* ************************ getters ****************************** */
	public State getStartState() { return this.startState; }
	public WamInterpreter getInterpreter() { return this.interpreter; }
	public InferenceExample getExample() {
		return this.example;
	}
}
