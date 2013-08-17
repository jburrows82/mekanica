package mekanism.common;

import ic2.api.energy.tile.IEnergySink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import mekanism.api.DynamicNetwork;
import mekanism.api.IStrictEnergyAcceptor;
import mekanism.api.ITransmitterNetwork;
import mekanism.api.Object3D;
import mekanism.api.TransmitterNetworkRegistry;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.Event;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.world.ChunkEvent;
import universalelectricity.core.block.IElectrical;
import universalelectricity.core.electricity.ElectricityPack;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.power.PowerHandler.PowerReceiver;
import buildcraft.api.power.PowerHandler.Type;
import cpw.mods.fml.common.FMLCommonHandler;

public class EnergyNetwork extends DynamicNetwork<IUniversalCable, TileEntity>
{	
	private double lastPowerScale = 0;
	private double joulesTransmitted = 0;
	private double joulesLastTick = 0;
	
	public EnergyNetwork(IUniversalCable... varCables)
	{
		transmitters.addAll(Arrays.asList(varCables));
		register();
	}
	
	public EnergyNetwork(Set<EnergyNetwork> networks)
	{
		for(EnergyNetwork net : networks)
		{
			if(net != null)
			{
				addAllTransmitters(net.transmitters);
				net.deregister();
			}
		}
		
		refresh();
		register();
	}
	
	public double getEnergyNeeded(ArrayList<TileEntity> ignored)
	{
		double totalNeeded = 0;
		
		for(TileEntity acceptor : getEnergyAcceptors())
		{
			if(!ignored.contains(acceptor))
			{
				if(acceptor instanceof IStrictEnergyAcceptor)
				{
					totalNeeded += (((IStrictEnergyAcceptor)acceptor).getMaxEnergy() - ((IStrictEnergyAcceptor)acceptor).getEnergy());
				}
				else if(acceptor instanceof IEnergySink)
				{
					totalNeeded += Math.min((((IEnergySink)acceptor).demandsEnergy()*Mekanism.FROM_IC2), (((IEnergySink)acceptor).getMaxSafeInput()*Mekanism.FROM_IC2));
				}
				else if(acceptor instanceof IPowerReceptor && Mekanism.hooks.BuildCraftLoaded)
				{
					totalNeeded += (((IPowerReceptor)acceptor).getPowerReceiver(acceptorDirections.get(acceptor).getOpposite()).powerRequest()*Mekanism.FROM_BC);
				}
				else if(acceptor instanceof IElectrical)
				{
					totalNeeded += ((IElectrical)acceptor).getRequest(acceptorDirections.get(acceptor))*Mekanism.FROM_UE;
				}
			}
		}
		
		return totalNeeded;
	}
	
	public double emit(double energyToSend, ArrayList<TileEntity> ignored)
	{
		double energyAvailable = energyToSend;		
		double sent;
		
		List availableAcceptors = Arrays.asList(getEnergyAcceptors().toArray());

		Collections.shuffle(availableAcceptors);

		if(!availableAcceptors.isEmpty())
		{
			int divider = availableAcceptors.size();
			double remaining = energyToSend % divider;
			double sending = (energyToSend-remaining)/divider;

			for(Object obj : availableAcceptors)
			{
				if(obj instanceof TileEntity && !ignored.contains(obj))
				{
					TileEntity acceptor = (TileEntity)obj;
					double currentSending = sending+remaining;
					
					remaining = 0;
					
					if(acceptor instanceof IStrictEnergyAcceptor)
					{
						energyToSend -= (currentSending - ((IStrictEnergyAcceptor)acceptor).transferEnergyToAcceptor(currentSending));
					}
					else if(acceptor instanceof IEnergySink)
					{
						double toSend = Math.min(currentSending, (((IEnergySink)acceptor).getMaxSafeInput()*Mekanism.FROM_IC2));
						energyToSend -= (toSend - (((IEnergySink)acceptor).injectEnergy(MekanismUtils.toIC2Direction(acceptorDirections.get(acceptor).getOpposite()), (int)(toSend*Mekanism.TO_IC2))*Mekanism.FROM_IC2));
					}
					else if(acceptor instanceof IPowerReceptor && Mekanism.hooks.BuildCraftLoaded)
					{
						PowerReceiver receiver = ((IPowerReceptor)acceptor).getPowerReceiver(acceptorDirections.get(acceptor).getOpposite());
		            	double electricityNeeded = Math.min(receiver.powerRequest(), receiver.getMaxEnergyStored() - receiver.getEnergyStored())*Mekanism.FROM_BC;
		            	double transferEnergy = Math.min(electricityNeeded, currentSending);
		            	receiver.receiveEnergy(Type.STORAGE, (float)(transferEnergy*Mekanism.TO_BC), acceptorDirections.get(acceptor).getOpposite());
		            	energyToSend -= transferEnergy;
					}
					else if(acceptor instanceof IElectrical)
					{
						double toSend = Math.min(currentSending, ((IElectrical)acceptor).getRequest(acceptorDirections.get(acceptor).getOpposite())*Mekanism.FROM_UE);
						ElectricityPack pack = ElectricityPack.getFromWatts((float)(toSend*Mekanism.TO_UE), ((IElectrical)acceptor).getVoltage());
						energyToSend -= ((IElectrical)acceptor).receiveElectricity(acceptorDirections.get(acceptor).getOpposite(), pack, true)*Mekanism.FROM_UE;
					}
				}
			}
			
			sent = energyAvailable - energyToSend;
			joulesTransmitted += sent;
		}
		
		return energyToSend;
	}
	
	public Set<TileEntity> getEnergyAcceptors()
	{
		Set<TileEntity> toReturn = new HashSet<TileEntity>();
		
		for(TileEntity acceptor : possibleAcceptors)
		{
			if(acceptor instanceof IStrictEnergyAcceptor)
			{
				if(((IStrictEnergyAcceptor)acceptor).canReceiveEnergy(acceptorDirections.get(acceptor).getOpposite()))
				{
					if((((IStrictEnergyAcceptor)acceptor).getMaxEnergy() - ((IStrictEnergyAcceptor)acceptor).getEnergy()) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IEnergySink)
			{
				if(((IEnergySink)acceptor).acceptsEnergyFrom(null, MekanismUtils.toIC2Direction(acceptorDirections.get(acceptor)).getInverse()))
				{
					if(Math.min((((IEnergySink)acceptor).demandsEnergy()*Mekanism.FROM_IC2), (((IEnergySink)acceptor).getMaxSafeInput()*Mekanism.FROM_IC2)) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IPowerReceptor && Mekanism.hooks.BuildCraftLoaded)
			{
				if(((IPowerReceptor)acceptor).getPowerReceiver(acceptorDirections.get(acceptor).getOpposite()) != null)
				{
					if((((IPowerReceptor)acceptor).getPowerReceiver(acceptorDirections.get(acceptor).getOpposite()).powerRequest()*Mekanism.FROM_BC) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
			else if(acceptor instanceof IElectrical)
			{
				if(((IElectrical)acceptor).canConnect(acceptorDirections.get(acceptor).getOpposite()))
				{
					if(((IElectrical)acceptor).getRequest(acceptorDirections.get(acceptor).getOpposite()) > 0)
					{
						toReturn.add(acceptor);
					}
				}
			}
		}
		
		return toReturn;
	}

	public void refresh()
	{
		Set<IUniversalCable> iterCables = (Set<IUniversalCable>) transmitters.clone();
		Iterator<IUniversalCable> it = iterCables.iterator();
		
		possibleAcceptors.clear();
		acceptorDirections.clear();

		while(it.hasNext())
		{
			IUniversalCable conductor = (IUniversalCable)it.next();

			if(conductor == null || ((TileEntity)conductor).isInvalid())
			{
				it.remove();
				transmitters.remove(conductor);
			}
			else {
				conductor.setNetwork(this);
			}
		}
		
		for(IUniversalCable cable : iterCables)
		{
			TileEntity[] acceptors = CableUtils.getConnectedEnergyAcceptors((TileEntity)cable);
		
			for(TileEntity acceptor : acceptors)
			{
				if(acceptor != null && !(acceptor instanceof IUniversalCable))
				{
					possibleAcceptors.add(acceptor);
					acceptorDirections.put(acceptor, ForgeDirection.getOrientation(Arrays.asList(acceptors).indexOf(acceptor)));
				}
			}
		}
		
		double currentPowerScale = getPowerScale();
		if(FMLCommonHandler.instance().getEffectiveSide().isServer())
		{
			lastPowerScale = currentPowerScale;

			MinecraftForge.EVENT_BUS.post(new EnergyTransferEvent(this, currentPowerScale));
		}
	}

	public void merge(EnergyNetwork network)
	{
		if(network != null && network != this)
		{
			Set<EnergyNetwork> networks = new HashSet<EnergyNetwork>();
			networks.add(this);
			networks.add(network);
			EnergyNetwork newNetwork = new EnergyNetwork(networks);
			newNetwork.refresh();
		}
	}

	public void split(IUniversalCable splitPoint)
	{
		if(splitPoint instanceof TileEntity)
		{
			removeTransmitter(splitPoint);
			
			TileEntity[] connectedBlocks = new TileEntity[6];
			boolean[] dealtWith = {false, false, false, false, false, false};
			
			for(ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
			{
				TileEntity sideTile = Object3D.get((TileEntity)splitPoint).getFromSide(direction).getTileEntity(((TileEntity)splitPoint).worldObj);
				
				if(sideTile != null)
				{
					connectedBlocks[Arrays.asList(ForgeDirection.values()).indexOf(direction)] = sideTile;
				}
			}

			for(int countOne = 0; countOne < connectedBlocks.length; countOne++)
			{
				TileEntity connectedBlockA = connectedBlocks[countOne];

				if(connectedBlockA instanceof IUniversalCable && !dealtWith[countOne])
				{
					NetworkFinder finder = new NetworkFinder(((TileEntity)splitPoint).worldObj, Object3D.get(connectedBlockA), Object3D.get((TileEntity)splitPoint));
					List<Object3D> partNetwork = finder.exploreNetwork();
					
					for(int countTwo = countOne + 1; countTwo < connectedBlocks.length; countTwo++)
					{
						TileEntity connectedBlockB = connectedBlocks[countTwo];
						
						if(connectedBlockB instanceof IUniversalCable && !dealtWith[countTwo])
						{
							if(partNetwork.contains(Object3D.get(connectedBlockB)))
							{
								dealtWith[countTwo] = true;
							}
						}
					}
					
					Set<IUniversalCable> newNetCables= new HashSet<IUniversalCable>();
					for(Object3D node : finder.iterated)
					{
						TileEntity nodeTile = node.getTileEntity(((TileEntity)splitPoint).worldObj);

						if(nodeTile instanceof IUniversalCable)
						{
							if(nodeTile != splitPoint)
							{
								newNetCables.add((IUniversalCable)nodeTile);
							}
						}
					}
					
					EnergyNetwork newNetwork = new EnergyNetwork(newNetCables.toArray(new IUniversalCable[0]));					
					newNetwork.refresh();
				}
			}
			
			deregister();
		}
	}
	
	public void fixMessedUpNetwork(IUniversalCable cable)
	{
		if(cable instanceof TileEntity)
		{
			NetworkFinder finder = new NetworkFinder(((TileEntity)cable).getWorldObj(), Object3D.get((TileEntity)cable), null);
			List<Object3D> partNetwork = finder.exploreNetwork();
			Set<IUniversalCable> newCables = new HashSet<IUniversalCable>();
			
			for(Object3D node : partNetwork)
			{
				TileEntity nodeTile = node.getTileEntity(((TileEntity)cable).worldObj);

				if(nodeTile instanceof IUniversalCable)
				{
					((IUniversalCable)nodeTile).removeFromNetwork();
					newCables.add((IUniversalCable)nodeTile);
				}
			}
			
			EnergyNetwork newNetwork = new EnergyNetwork(newCables.toArray(new IUniversalCable[0]));
			newNetwork.refresh();
			newNetwork.fixed = true;
			deregister();
		}
	}
	
	public static class NetworkFinder
	{
		public World worldObj;
		public Object3D start;
		
		public List<Object3D> iterated = new ArrayList<Object3D>();
		public List<Object3D> toIgnore = new ArrayList<Object3D>();
		
		public NetworkFinder(World world, Object3D location, Object3D... ignore)
		{
			worldObj = world;
			start = location;
			
			if(ignore != null)
			{
				toIgnore = Arrays.asList(ignore);
			}
		}

		public void loopAll(Object3D location)
		{
			if(location.getTileEntity(worldObj) instanceof IUniversalCable)
			{
				iterated.add(location);
			}
			
			for(ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
			{
				Object3D obj = location.getFromSide(direction);
				
				if(!iterated.contains(obj) && !toIgnore.contains(obj))
				{
					TileEntity tileEntity = obj.getTileEntity(worldObj);
					
					if(tileEntity instanceof IUniversalCable)
					{
						loopAll(obj);
					}
				}
			}
		}

		public List<Object3D> exploreNetwork()
		{
			loopAll(start);
			
			return iterated;
		}
	}
	
	public static class EnergyTransferEvent extends Event
	{
		public final EnergyNetwork energyNetwork;
		
		public final double power;
		
		public EnergyTransferEvent(EnergyNetwork network, double currentPower)
		{
			energyNetwork = network;
			power = currentPower;
		}
	}
	
	public static class NetworkLoader
	{
		@ForgeSubscribe
		public void onChunkLoad(ChunkEvent.Load event)
		{
			if(event.getChunk() != null)
			{
				for(Object obj : event.getChunk().chunkTileEntityMap.values())
				{
					if(obj instanceof TileEntity)
					{
						TileEntity tileEntity = (TileEntity)obj;
						
						if(tileEntity instanceof IUniversalCable)
						{
							((IUniversalCable)tileEntity).refreshNetwork();
						}
					}
				}
			}
		}
	}

	@Override
	public String toString()
	{
		return "[EnergyNetwork] " + transmitters.size() + " transmitters, " + possibleAcceptors.size() + " acceptors.";
	}

	public void tick()
	{
		clearJoulesTransmitted();
		
		//Fix weird behaviour periodically.
		if(!fixed)
		{
			++ticksSinceCreate;
			if(ticksSinceCreate > 1200)
			{
				ticksSinceCreate = 0;
				fixMessedUpNetwork(transmitters.iterator().next());
			}
		}
		
		double currentPowerScale = getPowerScale();
		if(currentPowerScale != lastPowerScale && FMLCommonHandler.instance().getEffectiveSide().isServer())
		{
			lastPowerScale = currentPowerScale;

			MinecraftForge.EVENT_BUS.post(new EnergyTransferEvent(this, currentPowerScale));
		}
	}
	
	public double getPowerScale()
	{
		return joulesLastTick == 0 ? 0 : Math.min(Math.ceil(Math.log10(getPower())*2)/10, 1);
	}
	
	public void clearJoulesTransmitted()
	{
		joulesLastTick = joulesTransmitted;
		joulesTransmitted = 0;
	}
	
	public double getPower()
	{
		return joulesLastTick * 20;
	}
}
