package edu.cmu.ml.proppr;

import static org.junit.Assert.*;
import edu.cmu.ml.proppr.examples.PosNegRWExample;
import edu.cmu.ml.proppr.learn.L2SRW;
import edu.cmu.ml.proppr.learn.SRW;
import edu.cmu.ml.proppr.learn.tools.ReLUWeightingScheme;
import edu.cmu.ml.proppr.util.Dictionary;
import edu.cmu.ml.proppr.util.ParamVector;
import edu.cmu.ml.proppr.util.SimpleParamVector;
import gnu.trove.map.TIntDoubleMap;
import gnu.trove.map.hash.TIntDoubleHashMap;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

public class GradientFinderTest extends RedBlueGraph {

	public Trainer trainer;
	public SRW srw;
	public TIntDoubleMap query;
	public ArrayList<PosNegRWExample> examples;
	
	public GradientFinderTest() {
		super(10);
	}
	
	public void initTrainer() {
		this.trainer = new Trainer(this.srw);
	}
	
	@Before
	public void setup() {
		super.setup();
		this.srw = new L2SRW();
		this.srw.setWeightingScheme(new ReLUWeightingScheme<String>());
		this.initTrainer();
		
		query = new TIntDoubleHashMap();
		query.put(nodes.getId("r0"),1.0);
		examples = new ArrayList<PosNegRWExample>();
		for (int k=0;k<this.magicNumber;k++) {
			for (int p=0;p<this.magicNumber;p++) {
				examples.add(new PosNegRWExample(brGraph, query, 
						new int[]{nodes.getId("b"+k)},
						new int[]{nodes.getId("r"+p)}));
			}
		}
	}
	
	public ParamVector train() {
		return this.trainer.findGradient(examples, new SimpleParamVector<String>());
	}

	@Test
	public void test() {
		ParamVector params = train();
		System.err.println(Dictionary.buildString(params,new StringBuilder(),"\n"));
		for (Object o : params.keySet()) {
			String f = (String) o;
			if (f.equals("tob")) assertTrue("tob "+f,params.get(o) <= 0);//this.srw.getWeightingScheme().defaultWeight());
			if (f.equals("tor")) assertTrue("tor "+f,params.get(o) >= 0);//this.srw.getWeightingScheme().defaultWeight());
		}
	}


}
