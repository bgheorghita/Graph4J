/*
 * Copyright (C) 2021 Faculty of Computer Science Iasi, Romania
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graph4j.alg.cut;

import gurobi.GRB;
import gurobi.GRBEnv;
import gurobi.GRBException;
import gurobi.GRBLinExpr;
import gurobi.GRBModel;
import gurobi.GRBVar;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.graph4j.Graph;
import org.graph4j.alg.connectivity.VertexConnectivityAlgorithm;
import org.graph4j.generate.GraphGenerator;

/**
 * Guropi ILP model for the Vertex Separator Problem (VSP).
 *
 * @author Cristian Frăsinaru
 */
public class GurobiVertexSeparator extends VertexSeparatorBase {

    protected GRBEnv env;
    protected GRBModel model;
    protected GRBVar x[], y[];

    public GurobiVertexSeparator(Graph graph) {
        super(graph);
    }

    public GurobiVertexSeparator(Graph graph, int maxShoreSize) {
        super(graph, maxShoreSize);
    }

    @Override
    public VertexSeparator getSeparator() {
        if (solution == null) {
            solve();
        }
        return solution;
    }

    protected void solve() {
        try {
            env = new GRBEnv(true);
            env.set(GRB.IntParam.OutputFlag, 0);
            env.start();

            model = new GRBModel(env);
            model.set(GRB.DoubleParam.MIPGapAbs, 0);
            model.set(GRB.DoubleParam.MIPGap, 0);
            //model.set(GRB.DoubleParam.TimeLimit, 60); //sec

            createModel();

            // Optimize model
            model.optimize();

            if (model.get(GRB.IntAttr.Status) == GRB.Status.OPTIMAL) {
                // Get the solution
                createSolution();
            } else {
                if (model.get(GRB.IntAttr.Status) == GRB.Status.TIME_LIMIT) {
                    //timeExpired = true;
                }
            }
            model.dispose();
            env.dispose();

        } catch (GRBException ex) {
            System.err.println(ex);
        }
    }

    //using greedy to obtain an upper bound for the separator set
    //vertices with degree <= 1 should be either in A or in B
    //if there is a solution of size k with v in C and deg(v) <=1, 
    //then there is also a solution of size k with v in A or B.
    private void createModel() throws GRBException {
        long t0 = System.currentTimeMillis();
        int n = graph.numVertices();

        x = new GRBVar[n]; //for A
        y = new GRBVar[n]; //for B
        for (int i = 0; i < n; i++) {
            x[i] = model.addVar(0.0, 1.0, 0.0, GRB.BINARY, "x[" + i + "]");
            y[i] = model.addVar(0.0, 1.0, 0.0, GRB.BINARY, "y[" + i + "]");
        }

        //a vertex cannot be both in A and in B        
        for (int i = 0; i < n; i++) {
            GRBLinExpr expr = new GRBLinExpr();
            expr.addTerm(1, x[i]);
            expr.addTerm(1, y[i]);
            int v = graph.vertexAt(i);
            if (graph.degree(v) <= 1) {
                model.addConstr(expr, GRB.EQUAL, 1, "vertex_" + i);
            } else {
                model.addConstr(expr, GRB.LESS_EQUAL, 1, "vertex_" + i);
            }
        }

        //there are no edges between A and B
        for (int v : graph.vertices()) {
            int vi = graph.indexOf(v);
            for (var it = graph.neighborIterator(v); it.hasNext();) {
                int u = it.next();
                int ui = graph.indexOf(u);
                GRBLinExpr expr1 = new GRBLinExpr();
                expr1.addTerm(1, x[vi]);
                expr1.addTerm(1, y[ui]);
                model.addConstr(expr1, GRB.LESS_EQUAL, 1, "noedge_" + v + "," + u);
                //
                GRBLinExpr expr2 = new GRBLinExpr();
                expr2.addTerm(1, x[ui]);
                expr2.addTerm(1, y[vi]);
                model.addConstr(expr2, GRB.LESS_EQUAL, 1, "noedge_" + u + "," + v);
            }
        }

        //1 <= |A| <= maxShoreSize
        GRBLinExpr sumA = new GRBLinExpr();
        for (int i = 0; i < n; i++) {
            sumA.addTerm(1, x[i]);
        }
        model.addConstr(sumA, GRB.LESS_EQUAL, maxShoreSize, "maxShoreSize_A");
        model.addConstr(sumA, GRB.GREATER_EQUAL, 1, "notempty_A");

        //1 <= |B| <= maxShoreSize
        GRBLinExpr sumB = new GRBLinExpr();
        for (int i = 0; i < n; i++) {
            sumB.addTerm(1, y[i]);
        }
        model.addConstr(sumB, GRB.LESS_EQUAL, maxShoreSize, "maxShoreSize_B");
        model.addConstr(sumB, GRB.GREATER_EQUAL, 1, "notempty_B");

        //objective |A| + |B|
        GRBLinExpr obj = new GRBLinExpr();
        for (int i = 0; i < n; i++) {
            obj.addTerm(1, x[i]);
            obj.addTerm(1, y[i]);
        }

        //the separator size should be lower than the greedy one
        //upper bound on separator size = lower bound on objective
        int greedySepSize = computeGreedySepSize();
        var lb = n - greedySepSize; //|A*|+|B*|
        model.addConstr(obj, GRB.GREATER_EQUAL, lb, "lowerBound");
        System.out.println("greedy separator size used: " + greedySepSize);

        //the separator size should be greater then the minimum vertex cut
        int ub = computeMinCutSize(lb);
        model.addConstr(obj, GRB.LESS_EQUAL, n - ub, "upperBound");
        System.out.println("local minimum cut size used: " + ub);

        //maximize |A| + |B|
        model.setObjective(obj, GRB.MAXIMIZE);

        long t1 = System.currentTimeMillis();
        System.out.println("Model ready in " + (t1 - t0) + " ms");
    }

    private int computeGreedySepSize() {
        var greedyAlg = new GreedyVertexSeparator(graph, maxShoreSize);
        return greedyAlg.getSeparator().separator().size();
    }

    private int computeMinCutSize(int lb) {
        long t0 = System.currentTimeMillis();
        var minCutAlg = new VertexConnectivityAlgorithm(graph);
        var minCutset = minCutAlg.getMinimumCut();
        System.out.println("global minimum cut size: " + minCutset.size());
        //analyze the minCutset
        /*
        var g = graph.copy();
        g.removeVertices(minCutset.vertices());
        for (var cc : new ConnectivityAlgorithm(g).getConnectedSets()) {
            System.out.println(cc.size() + " <= " + maxShoreSize + " = " + (cc.size() <= maxShoreSize));
        }*/
        /*
        int n = graph.numVertices();
        int[] alpha = new int[n];
        for (int i = 0; i < n; i++) {
            int v = graph.vertexAt(i);
            alpha[i] = minCutAlg.countMaximumDisjointPaths(v);
        }
        Arrays.sort(alpha);
        System.out.println(Arrays.toString(alpha));
        int ub = alpha[Math.min(lb + 1, alpha.length - 1)];
        */
        int ub = minCutset.size();
        long t1 = System.currentTimeMillis();
        System.out.println("MinCut ready in " + (t1 - t0) + " ms");
        return ub;
    }

    private void createSolution() {
        try {
            solution = new VertexSeparator(graph, maxShoreSize);
            solution.separator().addAll(graph.vertices());
            for (int i = 0, n = graph.numVertices(); i < n; i++) {
                int v = graph.vertexAt(i);
                if (x[i].get(GRB.DoubleAttr.X) > .00001) {
                    solution.leftShore().add(v);
                    solution.separator().remove(v);
                } else if (y[i].get(GRB.DoubleAttr.X) > .00001) {
                    solution.rightShore().add(v);
                    solution.separator().remove(v);
                }
            }
            assert solution.isComplete() && solution.isValid();
        } catch (GRBException ex) {
            Logger.getLogger(GurobiVertexSeparator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    //simple test
    public static void main(String args[]) {
        int n = 100;
        double p = 0.1;

        var g = GraphGenerator.randomGnp(n, p);
        var alg1 = new GreedyVertexSeparator(g);
        var sep1 = alg1.getSeparator();
        System.out.println("Greedy: separator size=" + sep1.separator().size() + "\n" + sep1);

        var alg2 = new GurobiVertexSeparator(g);
        var sep2 = alg2.getSeparator();
        System.out.println("Gurobi: separator size=" + sep2.separator().size() + "\n" + sep2);
    }
}