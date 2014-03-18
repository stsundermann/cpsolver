package net.sf.cpsolver.exam.heuristics;

import org.apache.log4j.Logger;

import net.sf.cpsolver.exam.model.Exam;
import net.sf.cpsolver.exam.model.ExamPlacement;
import net.sf.cpsolver.exam.neighbours.ExamRandomMove;
import net.sf.cpsolver.exam.neighbours.ExamRoomMove;
import net.sf.cpsolver.exam.neighbours.ExamTimeMove;
import net.sf.cpsolver.ifs.algorithms.GreatDeluge;
import net.sf.cpsolver.ifs.algorithms.HillClimber;
import net.sf.cpsolver.ifs.algorithms.SimulatedAnnealing;
import net.sf.cpsolver.ifs.assignment.Assignment;
import net.sf.cpsolver.ifs.assignment.context.AssignmentContext;
import net.sf.cpsolver.ifs.assignment.context.NeighbourSelectionWithContext;
import net.sf.cpsolver.ifs.heuristics.StandardNeighbourSelection;
import net.sf.cpsolver.ifs.model.Neighbour;
import net.sf.cpsolver.ifs.solution.Solution;
import net.sf.cpsolver.ifs.solver.Solver;
import net.sf.cpsolver.ifs.termination.TerminationCondition;
import net.sf.cpsolver.ifs.util.Callback;
import net.sf.cpsolver.ifs.util.DataProperties;
import net.sf.cpsolver.ifs.util.Progress;

/**
 * Examination timetabling neighbour selection. <br>
 * <br>
 * It consists of the following three phases:
 * <ul>
 * <li>Construction phase ({@link ExamConstruction} until all exams are
 * assigned)
 * <li>Hill-climbing phase ({@link ExamHillClimbing} until the given number if
 * idle iterations)
 * <li>Simulated annealing phase ({@link ExamSimulatedAnnealing} until timeout
 * is reached)
 * <ul>
 * <li>Or greate deluge phase (when Exam.GreatDeluge is true,
 * {@link ExamGreatDeluge} until timeout is reached)
 * </ul>
 * <li>At the end (when {@link TerminationCondition#canContinue(Solution)} is
 * false), the search is finished with one sweep of final phase (
 * {@link ExamHillClimbing} until the given number if idle iterations).
 * </ul>
 * <br>
 * <br>
 * 
 * @version ExamTT 1.2 (Examination Timetabling)<br>
 *          Copyright (C) 2008 - 2010 Tomas Muller<br>
 *          <a href="mailto:muller@unitime.org">muller@unitime.org</a><br>
 *          <a href="http://muller.unitime.org">http://muller.unitime.org</a><br>
 * <br>
 *          This library is free software; you can redistribute it and/or modify
 *          it under the terms of the GNU Lesser General Public License as
 *          published by the Free Software Foundation; either version 3 of the
 *          License, or (at your option) any later version. <br>
 * <br>
 *          This library is distributed in the hope that it will be useful, but
 *          WITHOUT ANY WARRANTY; without even the implied warranty of
 *          MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *          Lesser General Public License for more details. <br>
 * <br>
 *          You should have received a copy of the GNU Lesser General Public
 *          License along with this library; if not see
 *          <a href='http://www.gnu.org/licenses/'>http://www.gnu.org/licenses/</a>.
 */
public class ExamNeighbourSelection extends NeighbourSelectionWithContext<Exam, ExamPlacement, ExamNeighbourSelection.Context> implements TerminationCondition<Exam, ExamPlacement> {
    private static Logger sLog = Logger.getLogger(ExamNeighbourSelection.class);
    private ExamColoringConstruction iColor = null;
    private ExamConstruction iCon = null;
    private StandardNeighbourSelection<Exam, ExamPlacement> iStd = null;
    private SimulatedAnnealing<Exam, ExamPlacement> iSA = null;
    private HillClimber<Exam, ExamPlacement> iHC = null;
    private HillClimber<Exam, ExamPlacement> iFin = null;
    private GreatDeluge<Exam, ExamPlacement> iGD = null;
    private boolean iUseGD = false;
    private Progress iProgress = null;
    private Callback iFinalPhaseFinished = null;
    private TerminationCondition<Exam, ExamPlacement> iTerm = null;
    private boolean iFinalPhase = false;

    /**
     * Constructor
     * 
     * @param properties
     *            problem properties
     */
    public ExamNeighbourSelection(DataProperties properties) {
        if (properties.getPropertyBoolean("Exam.ColoringConstruction", false))
            iColor = new ExamColoringConstruction(properties);
        iCon = new ExamConstruction(properties);
        try {
            iStd = new StandardNeighbourSelection<Exam, ExamPlacement>(properties);
            iStd.setVariableSelection(new ExamUnassignedVariableSelection(properties));
            iStd.setValueSelection(new ExamTabuSearch(properties));
        } catch (Exception e) {
            sLog.error("Unable to initialize standard selection, reason: " + e.getMessage(), e);
            iStd = null;
        }
        properties.setProperty("SimulatedAnnealing.Neighbours", ExamRandomMove.class.getName() + ";" + ExamRoomMove.class.getName() + ";" + ExamTimeMove.class.getName());
        iSA = new SimulatedAnnealing<Exam, ExamPlacement>(properties);
        properties.setProperty("HillClimber.Neighbours", ExamRandomMove.class.getName() + ";" + ExamRoomMove.class.getName() + ";" + ExamTimeMove.class.getName());
        iHC = new HillClimber<Exam, ExamPlacement>(properties);
        iFin = new HillClimber<Exam, ExamPlacement>(properties); iFin.setPhase("Finalization");
        properties.setProperty("GreatDeluge.Neighbours", ExamRandomMove.class.getName() + ";" + ExamRoomMove.class.getName() + ";" + ExamTimeMove.class.getName());
        iGD = new GreatDeluge<Exam, ExamPlacement>(properties);
        iUseGD = properties.getPropertyBoolean("Exam.GreatDeluge", iUseGD);
    }

    /**
     * Initialization
     */
    @Override
    public void init(Solver<Exam, ExamPlacement> solver) {
        super.init(solver);
        if (iColor != null)
            iColor.init(solver);
        iCon.init(solver);
        iStd.init(solver);
        iSA.init(solver);
        iHC.init(solver);
        iFin.init(solver);
        iGD.init(solver);
        if (iTerm == null) {
            iTerm = solver.getTerminationCondition();
            solver.setTerminalCondition(this);
        }
        iFinalPhase = false;
        iProgress = Progress.getInstance(solver.currentSolution().getModel());
    }

    /**
     * Neighbour selection. It consists of the following three phases:
     * <ul>
     * <li>Construction phase ({@link ExamConstruction} until all exams are
     * assigned)
     * <li>Hill-climbing phase ({@link ExamHillClimbing} until the given number
     * if idle iterations)
     * <li>Simulated annealing phase ({@link ExamSimulatedAnnealing} until
     * timeout is reached)
     * </ul>
     */
    @SuppressWarnings("fallthrough")
    @Override
    public Neighbour<Exam, ExamPlacement> selectNeighbour(Solution<Exam, ExamPlacement> solution) {
        Neighbour<Exam, ExamPlacement> n = null;
        Context phase = getContext(solution.getAssignment());
        if (isFinalPhase())
            phase.setPhase(9999);
        switch (phase.getPhase()) {
            case -1:
                phase.setPhase(0);
                sLog.info("***** construction phase *****");
                if (iColor != null) {
                    n = iColor.selectNeighbour(solution);
                    if (n != null) return n;
                }
            case 0:
                n = iCon.selectNeighbour(solution);
                if (n != null)
                    return n;
                if (solution.getAssignment().nrAssignedVariables() > 0)
                    iProgress.setPhase("Searching for initial solution...", solution.getModel().variables().size());
                phase.setPhase(1);
                sLog.info("***** cbs/tabu-search phase *****");
            case 1:
                if (iStd != null && solution.getModel().variables().size() > solution.getAssignment().nrAssignedVariables()) {
                    iProgress.setProgress(solution.getModel().variables().size() - solution.getModel().getBestUnassignedVariables());
                    n = iStd.selectNeighbour(solution);
                    if (n != null)
                        return n;
                }
                phase.setPhase(2);
                sLog.info("***** hill climbing phase *****");
            case 2:
                n = iHC.selectNeighbour(solution);
                if (n != null)
                    return n;
                phase.setPhase(3);
                sLog.info("***** " + (iUseGD ? "great deluge" : "simulated annealing") + " phase *****");
            case 3:
                if (iUseGD)
                    return iGD.selectNeighbour(solution);
                else
                    return iSA.selectNeighbour(solution);
            case 9999:
                n = iFin.selectNeighbour(solution);
                if (n != null)
                    return n;
                phase.setPhase(-1);
                if (iFinalPhaseFinished != null && iTerm.canContinue(solution))
                    iFinalPhaseFinished.execute();
                phase.setCanContinue(false);
            default:
                return null;
        }
    }

    /**
     * Set final phase
     * 
     * @param finalPhaseFinished
     *            to be called when the final phase is finished
     **/
    public void setFinalPhase(Callback finalPhaseFinished) {
        sLog.info("***** final phase *****");
        iFinalPhaseFinished = finalPhaseFinished;
        iFinalPhase = true;
    }

    /** Is final phase */
    public boolean isFinalPhase() {
        return iFinalPhase;
    }

    /** Termination condition (i.e., has final phase finished) */
    @Override
    public boolean canContinue(Solution<Exam, ExamPlacement> currentSolution) {
        return getContext(currentSolution.getAssignment()).isCanContinue();
    }
    
    @Override
    public Context createAssignmentContext(Assignment<Exam, ExamPlacement> assignment) {
        return new Context();
    }

    public class Context implements AssignmentContext {
        private int iPhase = -1;
        private boolean iCanContinue = true;

        public int getPhase() { return iPhase; }
        public void setPhase(int phase) { iPhase = phase; }
        
        public boolean isCanContinue() { return iCanContinue; }
        public void setCanContinue(boolean canContinue) { iCanContinue = canContinue; }
    }
}
