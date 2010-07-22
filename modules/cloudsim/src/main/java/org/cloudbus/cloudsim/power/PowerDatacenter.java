/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation
 *               of Parallel and Distributed Systems such as Clusters and Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2008, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.power;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.predicates.PredicateType;

/**
 * CloudSim Datacentre class is a CloudResource whose hostList
 * are virtualized. It deals with processing of VM queries (i.e., handling
 * of VMs) instead of processing Cloudlet-related queries. So, even though an
 * AllocPolicy will be instantiated (in the init() method of the superclass,
 * it will not be used, as processing of cloudlets are handled by the CloudletScheduler
 * and processing of VirtualMachines are handled by the VMAllocationPolicy.
 *
 * @author       Rodrigo N. Calheiros
 * @since        CloudSim Toolkit 4.3
 * @invariant $none
 */
public class PowerDatacenter extends Datacenter {

	/** The power. */
	private double power;

	/** The disable migrations. */
	private boolean disableMigrations;

	/** The cloudlet submited. */
	private double cloudletSubmitted;

	/** The migration count. */
	private int migrationCount;

	/**
	 * Instantiates a new datacenter.
	 *
	 * @param name the name
	 * @param characteristics the res config
	 * @param schedulingInterval the scheduling interval
	 * @param utilizationBound the utilization bound
	 * @param vmAllocationPolicy the vm provisioner
	 * @param storageList the storage list
	 *
	 * @throws Exception the exception
	 */
	public PowerDatacenter(
			String name,
			DatacenterCharacteristics characteristics,
			VmAllocationPolicy vmAllocationPolicy,
			List<Storage> storageList,
			double schedulingInterval) throws Exception {
		super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);

		setPower(0.0);
		setDisableMigrations(false);
		setCloudletSubmitted(-1);
		setMigrationCount(0);
	}

	/**
	 * Updates processing of each cloudlet running in this PowerDatacenter. It is necessary because
	 * Hosts and VirtualMachines are simple objects, not entities. So, they don't receive events
	 * and updating cloudlets inside them must be called from the outside.
	 *
	 * @pre $none
	 * @post $none
	 */
	@Override
	protected void updateCloudletProcessing() {
//		if (isInMigration()) {
//			CloudSim.cancelAll(getId(), new PredicateType(CloudSimTags.VM_DATACENTER_EVENT));
//			schedule(getId(), getSchedulingInterval(), CloudSimTags.VM_DATACENTER_EVENT);
//			return;
//		}
		if (getCloudletSubmitted() == -1 || getCloudletSubmitted() == CloudSim.clock()) {
			CloudSim.cancelAll(getId(), new PredicateType(CloudSimTags.VM_DATACENTER_EVENT));
			schedule(getId(), getSchedulingInterval(), CloudSimTags.VM_DATACENTER_EVENT);
			return;
		}
		double currentTime = CloudSim.clock();
		double timeframePower = 0.0;

		// if some time passed since last processing
		if (currentTime > getLastProcessTime()) {
			double timeDiff = currentTime - getLastProcessTime();
			double minTime = Double.MAX_VALUE;

			Log.printLine("\n");

			for (PowerHost host : this.<PowerHost>getHostList()) {

				if (!Log.isDisabled()) {
					Log.print(String.format("%.2f: Host #%d\n", CloudSim.clock(), host.getId()));
				}

				double hostPower = 0.0;

				if (host.getUtilizationOfCpu() > 0) {
					try {
						hostPower = host.getPower() * timeDiff;
						timeframePower += hostPower;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				if (!Log.isDisabled()) {
					Log.print(String.format("%.2f: Host #%d utilization is %.2f%%\n", CloudSim.clock(), host.getId(), host.getUtilizationOfCpu() * 100));
					Log.print(String.format("%.2f: Host #%d energy is %.2f W*sec\n", CloudSim.clock(), host.getId(), hostPower));
				}
			}

			if (!Log.isDisabled()) {
				Log.print(String.format("\n%.2f: Consumed energy is %.2f W*sec\n\n", CloudSim.clock(), timeframePower));
			}

			Log.printLine("\n\n--------------------------------------------------------------\n\n");

			for (PowerHost host : this.<PowerHost>getHostList()) {
				if (!Log.isDisabled()) {
					Log.print(String.format("\n%.2f: Host #%d\n", CloudSim.clock(), host.getId()));
				}

				double time = host.updateVmsProcessing(currentTime); // inform VMs to update processing
				if (time < minTime) {
					minTime = time;
				}

				if (!Log.isDisabled()) {
					Log.print(String.format("%.2f: Host #%d utilization is %.2f%%\n", CloudSim.clock(), host.getId(), host.getUtilizationOfCpu() * 100));
				}
			}

			setPower(getPower() + timeframePower);

			/** Remove completed VMs **/
			for (PowerHost host : this.<PowerHost>getHostList()) {
				for (Vm vm : host.getCompletedVms()) {
					getVmAllocationPolicy().deallocateHostForVm(vm);
					getVmList().remove(vm);
					Log.printLine("VM #" + vm.getId() + " has been deallocated from host #" + host.getId());
				}
			}

			Log.printLine();

			if (!isDisableMigrations()) {
				List<Map<String, Object>> migrationMap = getVmAllocationPolicy().optimizeAllocation(getVmList());

				for (Map<String, Object> migrate : migrationMap) {
					Vm vm = (Vm) migrate.get("vm");
					PowerHost targetHost = (PowerHost) migrate.get("host");
					PowerHost oldHost = (PowerHost) vm.getHost();

					targetHost.addMigratingInVm(vm);

					if (targetHost.getVmList().size() + targetHost.getVmsMigratingIn().size() > 16) {
						Log.printLine("problem");
					}

					if (!Log.isDisabled()) {
						if (oldHost == null) {
							Log.print(String.format("%.2f: Migration of VM #%d to Host #%d is started\n", CloudSim.clock(), vm.getId(), targetHost.getId()));
						} else {
							Log.print(String.format("%.2f: Migration of VM #%d from Host #%d to Host #%d is started\n", CloudSim.clock(), vm.getId(), oldHost.getId(), targetHost.getId()));
							//oldHost.vmDestroy(vm);
						}
					}

					incrementMigrationCount();

					vm.setInMigration(true);

					/** VM migration delay = RAM / bandwidth + C    (C = 10 sec) **/
					send(getId(), (double) vm.getRam() / (vm.getBw() / 8000) + 0, CloudSimTags.VM_MIGRATE, migrate);
				}
			}

			// schedules an event to the next time
			if (minTime != Double.MAX_VALUE) {
//				if (minTime > currentTime + 0.01 && minTime < getSchedulingInterval()) {
//					send(getId(), minTime - currentTime, CloudSimTags.VM_DATACENTER_EVENT);
//				} else {
					CloudSim.cancelAll(getId(), new PredicateType(CloudSimTags.VM_DATACENTER_EVENT));
					send(getId(), getSchedulingInterval(), CloudSimTags.VM_DATACENTER_EVENT);
//				}
			}

			setLastProcessTime(currentTime);
		}
	}

	/* (non-Javadoc)
	 * @see cloudsim.Datacenter#processCloudletSubmit(cloudsim.core.SimEvent, boolean)
	 */
	@Override
	protected void processCloudletSubmit(SimEvent ev, boolean ack) {
		super.processCloudletSubmit(ev, ack);
		setCloudletSubmitted(CloudSim.clock());
	}

	/**
	 * Gets the power.
	 *
	 * @return the power
	 */
	public double getPower() {
		return power;
	}

	/**
	 * Sets the power.
	 *
	 * @param power the new power
	 */
	protected void setPower(double power) {
		this.power = power;
	}

	/**
	 * Checks if PowerDatacenter is in migration.
	 *
	 * @return true, if PowerDatacenter is in migration
	 */
	protected boolean isInMigration() {
		boolean result = false;
		for (Vm vm : getVmList()) {
			if (vm.isInMigration()) {
				result = true;
				break;
			}
		}
		return result;
	}

	/**
	 * Gets the under allocated mips.
	 *
	 * @return the under allocated mips
	 */
	public Map<String, List<List<Double>>> getUnderAllocatedMips() {
		Map<String, List<List<Double>>> underAllocatedMips = new HashMap<String, List<List<Double>>>();
		for (PowerHost host : this.<PowerHost>getHostList()) {
			for (Entry<String, List<List<Double>>> entry : host.getUnderAllocatedMips().entrySet()) {
				if (!underAllocatedMips.containsKey(entry.getKey())) {
					underAllocatedMips.put(entry.getKey(), new ArrayList<List<Double>>());
				}
				underAllocatedMips.get(entry.getKey()).addAll(entry.getValue());

			}
		}
		return underAllocatedMips;
	}

	/**
	 * Checks if is disable migrations.
	 *
	 * @return true, if is disable migrations
	 */
	public boolean isDisableMigrations() {
		return disableMigrations;
	}

	/**
	 * Sets the disable migrations.
	 *
	 * @param disableMigrations the new disable migrations
	 */
	public void setDisableMigrations(boolean disableMigrations) {
		this.disableMigrations = disableMigrations;
	}

	/**
	 * Checks if is cloudlet submited.
	 *
	 * @return true, if is cloudlet submited
	 */
	protected double getCloudletSubmitted() {
		return cloudletSubmitted;
	}

	/**
	 * Sets the cloudlet submited.
	 *
	 * @param cloudletSubmitted the new cloudlet submited
	 */
	protected void setCloudletSubmitted(double cloudletSubmitted) {
		this.cloudletSubmitted = cloudletSubmitted;
	}

	/**
	 * Gets the migration count.
	 *
	 * @return the migration count
	 */
	public int getMigrationCount() {
		return migrationCount;
	}

	/**
	 * Sets the migration count.
	 *
	 * @param migrationCount the new migration count
	 */
	protected void setMigrationCount(int migrationCount) {
		this.migrationCount = migrationCount;
	}

	/**
	 * Increment migration count.
	 */
	protected void incrementMigrationCount() {
		setMigrationCount(getMigrationCount() + 1);
	}


}
