"""Loads a PyTorch checkpoint without running arbitrary pickled code (weights_only), allowing only the numpy scalar
and dtype globals some research checkpoints carry in their metadata."""
import numpy as np
import torch


def load(path):
    scalar = np._core.multiarray.scalar if hasattr(np, "_core") else np.core.multiarray.scalar
    dtypes = [type(np.dtype(t)) for t in ("float32", "float64", "int64", "int32", "bool")]
    allowed = [(scalar, "numpy.core.multiarray.scalar"), scalar, np.dtype, *dtypes]
    with torch.serialization.safe_globals(allowed):
        return torch.load(path, map_location="cpu", weights_only=True)
